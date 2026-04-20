package com.pcsatish.cameraw

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pcsatish.cameraw.camera.CameraManager
import com.pcsatish.cameraw.camera.CameraParameters
import com.pcsatish.cameraw.camera.CameraState
import com.pcsatish.cameraw.monitoring.PerformanceMonitor
import com.pcsatish.cameraw.ui.CameraSidebar
import com.pcsatish.cameraw.ui.ControlMode
import com.pcsatish.cameraw.ui.ControlRuler
import com.pcsatish.cameraw.ui.GalleryPreview
import com.pcsatish.cameraw.ui.ModeSelectorRow
import com.pcsatish.cameraw.ui.ShutterButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var cameraManager: CameraManager
    @Inject lateinit var performanceMonitor: PerformanceMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Edge-to-Edge / Immersive
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            val context = LocalContext.current
            var hasCameraPermission by remember { 
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ) 
            }
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted -> hasCameraPermission = granted }
            )

            LaunchedEffect(hasCameraPermission) {
                if (!hasCameraPermission) {
                    launcher.launch(Manifest.permission.CAMERA)
                } else {
                    performanceMonitor.start()
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    performanceMonitor.stop()
                }
            }

            val provider by cameraManager.currentProvider.collectAsState()
            val cameraState by provider.state.collectAsState()
            val cameraIds = remember { cameraManager.getAvailableCameras() }
            val scope = rememberCoroutineScope()

            var activeSurface by remember { mutableStateOf<Surface?>(null) }
            
            // ISP State
            var iso by remember { mutableStateOf(100f) }
            var exposureNs by remember { mutableStateOf(10_000_000L) } // 1/100s
            var wbKelvin by remember { mutableStateOf(5000) }
            var focusDistance by remember { mutableStateOf(0f) } // 0.0 = Auto/Infinity
            var selectedMode by remember { mutableStateOf<ControlMode?>(null) }

            LaunchedEffect(iso, exposureNs, wbKelvin, focusDistance) {
                if (cameraState is CameraState.Opened) {
                    provider.updateParameters(
                        CameraParameters(
                            iso = iso.toInt(),
                            exposureTimeNs = exposureNs,
                            focusDistance = if (focusDistance == 0f) null else focusDistance,
                            whiteBalanceMode = if (wbKelvin == 0) android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO else android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_OFF
                            // Note: Kelvin to Gains conversion will be handled inside the provider or a utility
                        )
                    )
                }
            }

            LaunchedEffect(cameraState) {
                if (cameraState is CameraState.Initial && cameraIds.isNotEmpty()) {
                    provider.open(cameraIds.first())
                }
            }

            LaunchedEffect(cameraState, activeSurface) {
                val surface = activeSurface
                if (cameraState is CameraState.Opened && surface != null) {
                    provider.startPreview(surface)
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (hasCameraPermission) {
                        val previewSize by provider.previewSize.collectAsState()
                        val sensorOrientation by provider.sensorOrientation.collectAsState()
                        val luma by provider.luma.collectAsState()
                        val fps by provider.fps.collectAsState()
                        val metrics by performanceMonitor.metrics.collectAsState()

                        val displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            display?.rotation ?: Surface.ROTATION_0
                        } else {
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.rotation
                        }
                        val rotationDegrees = when (displayRotation) {
                            Surface.ROTATION_0 -> 0
                            Surface.ROTATION_90 -> 90
                            Surface.ROTATION_180 -> 180
                            Surface.ROTATION_270 -> 270
                            else -> 0
                        }

                        // LG-H930 HAL provides a pre-rotated upright buffer.
                        // We neutralize the 90° sensor orientation to avoid double-correction.
                        val isLgH930 = android.os.Build.MODEL == "LG-H930"
                        val logicalRotation = (sensorOrientation - rotationDegrees + 360) % 360
                        val totalRotation = if (isLgH930) {
                            (logicalRotation - 90 + 360) % 360
                        } else {
                            logicalRotation
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { context ->
                                    TextureView(context).apply {
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                                Log.d("MainActivity", "Surface Available: ${width}x${height}")
                                                val size = previewSize ?: Size(width, height)
                                                st.setDefaultBufferSize(size.width, size.height)
                                                val surface = Surface(st)
                                                activeSurface = surface

                                                // Force a preview start whenever the surface is available
                                                // to prevent black screen on rotation/lifecycle changes.
                                                if (cameraState is CameraState.Opened) {
                                                    scope.launch {
                                                        provider.startPreview(surface)
                                                    }
                                                }
                                            }
                                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                                Log.d("MainActivity", "Surface Size Changed: ${width}x${height}")
                                            }
                                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                Log.d("MainActivity", "Surface Destroyed")
                                                activeSurface = null
                                                return true
                                            }
                                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                        }
                                    }
                                },
                                update = { view ->
                                    val size = previewSize ?: return@AndroidView
                                    if (view.width <= 0 || view.height <= 0) return@AndroidView

                                    // Use post to ensure we have the final measured dimensions
                                    // after an orientation change.
                                    view.post {
                                        val matrix = Matrix()
                                        val viewWidth = view.width.toFloat()
                                        val viewHeight = view.height.toFloat()

                                        // 1. Calculate buffer dimensions based on logical world orientation
                                        // LG-H930 Note: The HAL always outputs an "upright" buffer (90deg rotated).
                                        // We must use the physical buffer dimensions for scaling.
                                        val isLgH930 = android.os.Build.MODEL == "LG-H930"
                                        val bufferWidth = if (isLgH930 || logicalRotation % 180 == 90) size.height.toFloat() else size.width.toFloat()
                                        val bufferHeight = if (isLgH930 || logicalRotation % 180 == 90) size.width.toFloat() else size.height.toFloat()

                                        // 2. Center-Crop Scale
                                        val scaleX = viewWidth / bufferWidth
                                        val scaleY = viewHeight / bufferHeight
                                        val maxScale = Math.max(scaleX, scaleY)

                                        // 3. Apply Transform
                                        matrix.setScale(
                                            (bufferWidth / viewWidth) * maxScale,
                                            (bufferHeight / viewHeight) * maxScale,
                                            viewWidth / 2f,
                                            viewHeight / 2f
                                        )

                                        if (totalRotation != 0) {
                                            matrix.postRotate(totalRotation.toFloat(), viewWidth / 2f, viewHeight / 2f)
                                        }

                                        view.setTransform(matrix)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Telemetry Overlay
                            Column(modifier = Modifier.align(Alignment.TopStart).padding(top = 32.dp, start = 8.dp).background(Color.Black.copy(alpha = 0.5f))) {
                                Text("Sensor: $sensorOrientation° | Total: $totalRotation°", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                Text("Luma: ${"%.2f".format(luma)} | FPS: $fps", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                Text("CPU: ${"%.1f".format(metrics.cpuLoad)}% | RAM: ${metrics.memoryUsageMb}MB | Thrm: ${metrics.thermalStatus}", color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                            }

                            // Camera Sidebar (Left side icons) shifted down
                            CameraSidebar(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(bottom = 160.dp)
                            )

                            // Professional Camera Controls Overlay (Right side dial)
                            selectedMode?.let { mode ->
                                ControlRuler(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    selectedMode = mode,
                                    iso = iso.toInt(),
                                    onIsoChange = { iso = it.toFloat() },
                                    exposureNs = exposureNs,
                                    onExposureChange = { exposureNs = it },
                                    wbKelvin = wbKelvin,
                                    onWbChange = { wbKelvin = it },
                                    focusDistance = focusDistance,
                                    onFocusChange = { focusDistance = it }
                                )
                            }

                            // Professional Shutter & Gallery Controls
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Mode Selector Row above Shutter
                                ModeSelectorRow(
                                    selectedMode = selectedMode,
                                    onModeClick = { mode ->
                                        selectedMode = if (selectedMode == mode) null else mode
                                    },
                                    iso = iso.toInt(),
                                    exposureNs = exposureNs,
                                    wbKelvin = wbKelvin,
                                    focusDistance = focusDistance
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                                ) {
                                    GalleryPreview(onClick = { /* TODO: Open Gallery */ })
                                    ShutterButton(onClick = { scope.launch { provider.captureStill() } })
                                    // Placeholder for switch camera or last thumb
                                    Box(modifier = Modifier.size(56.dp)) 
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Camera Permission Required")
                                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                                    Text("Grant Permission")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
