package com.pcsatish.cameraw.camera

import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IpCameraProvider @Inject constructor() : CameraProvider {
    private val _state = MutableStateFlow<CameraState>(CameraState.Initial)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _previewSize = MutableStateFlow<Size?>(Size(1280, 720))
    override val previewSize: StateFlow<Size?> = _previewSize.asStateFlow()

    private val _sensorOrientation = MutableStateFlow(0)
    override val sensorOrientation: StateFlow<Int> = _sensorOrientation.asStateFlow()

    private val _luma = MutableStateFlow(0.0)
    override val luma: StateFlow<Double> = _luma.asStateFlow()

    private val _fps = MutableStateFlow(0)
    override val fps: StateFlow<Int> = _fps.asStateFlow()

    override suspend fun open(cameraId: String) {
        _state.value = CameraState.Opening
        // Mocking an IP camera connection
        kotlinx.coroutines.delay(1000)
        _state.value = CameraState.Opened
    }

    override suspend fun startPreview(surface: Surface) {
        // Mock preview - maybe show a placeholder stream
    }

    override suspend fun close() {
        _state.value = CameraState.Closing
        _state.value = CameraState.Closed
    }

    override suspend fun captureStill(onCaptureStarted: () -> Unit) {
        onCaptureStarted()
        // Mock capture
    }

    override suspend fun updateParameters(params: CameraParameters) {
        // Mock parameter update
    }
}
