package com.pcsatish.cameraw

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pcsatish.cameraw.camera.CameraManager
import com.pcsatish.cameraw.camera.CameraParameters
import com.pcsatish.cameraw.camera.CameraState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var cameraManager: CameraManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var hasCameraPermission by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted -> hasCameraPermission = granted }
            )

            LaunchedEffect(Unit) {
                launcher.launch(Manifest.permission.CAMERA)
            }

            val provider by cameraManager.currentProvider.collectAsState()
            val cameraState by provider.state.collectAsState()
            val cameraIds = remember { cameraManager.getAvailableCameras() }
            val scope = rememberCoroutineScope()

            var activeSurface by remember { mutableStateOf<Surface?>(null) }
            
            // ISP State
            var iso by remember { mutableStateOf(100f) }
            var exposureNs by remember { mutableStateOf(10_000_000L) } // 1/100s

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
                        Column {
                            val previewSize by provider.previewSize.collectAsState()
                            val sensorOrientation by provider.sensorOrientation.collectAsState()
                            val luma by provider.luma.collectAsState()
                            val fps by provider.fps.collectAsState()

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

                            Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
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
                                        
                                        val matrix = Matrix()
                                        val viewWidth = view.width.toFloat()
                                        val viewHeight = view.height.toFloat()
                                        
                                        // 1. Calculate buffer dimensions based on logical world orientation
                                        // Even if totalRotation is 0 (LG fix), the pixels are packed 
                                        // according to logicalRotation (90 in portrait).
                                        val bufferWidth = if (logicalRotation % 180 == 90) size.height.toFloat() else size.width.toFloat()
                                        val bufferHeight = if (logicalRotation % 180 == 90) size.width.toFloat() else size.height.toFloat()

                                        // 2. Center-Crop Scale
                                        val scaleX = viewWidth / bufferWidth
                                        val scaleY = viewHeight / bufferHeight
                                        val maxScale = Math.max(scaleX, scaleY)
                                        
                                        // 3. Apply Transform
                                        // Use setScale to counteract the implicit FitXY stretch
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
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                
                                // Telemetry Overlay
                                Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f))) {
                                    Text("Sensor: $sensorOrientation° | Total: $totalRotation°", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    Text("Luma: ${"%.2f".format(luma)} | FPS: $fps", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            // ISP Controls
                            Column(modifier = Modifier.weight(0.4f).padding(16.dp).verticalScroll(rememberScrollState())) {
                                Text("ISP Controls", style = MaterialTheme.typography.titleMedium)
                                
                                Text("ISO: ${iso.toInt()}")
                                Slider(value = iso, onValueChange = { iso = it }, valueRange = 50f..3200f)
                                
                                Text("Exposure: 1/${(1_000_000_000L / exposureNs)}s")
                                Slider(value = exposureNs.toFloat(), onValueChange = { exposureNs = it.toLong() }, valueRange = 100_000f..100_000_000f)

                                Button(
                                    onClick = { 
                                        scope.launch { 
                                            provider.updateParameters(CameraParameters(iso = iso.toInt(), exposureTimeNs = exposureNs)) 
                                        } 
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Apply ISP Parameters")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = { scope.launch { provider.captureStill() } },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Capture High-Res Still")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
