package com.pcsatish.cameraw

import android.Manifest
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.pcsatish.cameraw.camera.CameraConstants
import com.pcsatish.cameraw.camera.CameraManager
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

            var activeSurface by remember { mutableStateOf<Surface?>(null) }

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
                            val totalRotation = (sensorOrientation - rotationDegrees + 360) % 360

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                AndroidView(
                                    factory = { context ->
                                        TextureView(context).apply {
                                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                                    val size = previewSize ?: Size(width, height)
                                                    st.setDefaultBufferSize(size.width, size.height)
                                                    activeSurface = Surface(st)
                                                }
                                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                    activeSurface?.release()
                                                    activeSurface = null
                                                    return true
                                                }
                                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                            }
                                        }
                                    },
                                    update = { view ->
                                        // Intentionally leaving matrix empty to document the failure
                                        view.setTransform(null)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                
                                Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))) {
                                    Text("Sensor: $sensorOrientation", color = androidx.compose.ui.graphics.Color.White)
                                    Text("Display: $rotationDegrees", color = androidx.compose.ui.graphics.Color.White)
                                    Text("Total: $totalRotation", color = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
