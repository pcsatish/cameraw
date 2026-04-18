package com.pcsatish.cameraw.camera

import android.util.Size
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for camera provider implementations (Native Camera2, IP Camera, etc.)
 */
interface CameraProvider {
    /**
     * Current state of the camera (Initial, Opening, Opened, Closing, Closed, Error)
     */
    val state: StateFlow<CameraState>
    val previewSize: StateFlow<Size?>
    val sensorOrientation: StateFlow<Int>
    val luma: StateFlow<Double>
    val fps: StateFlow<Int>

    /**
     * Opens the camera
     */
    suspend fun open(cameraId: String)

    /**
     * Starts the preview stream to the given surface
     */
    suspend fun startPreview(surface: android.view.Surface)

    /**
     * Closes the camera and stops all processing
     */
    suspend fun close()

    /**
     * Captures a still image from the current stream
     */
    suspend fun captureStill(onCaptureStarted: () -> Unit = {})

    /**
     * Updates ISP/Camera parameters (Exposure, ISO, Focus, etc.)
     */
    suspend fun updateParameters(params: CameraParameters)
}

sealed class CameraState(val name: String) {
    object Initial : CameraState("Initial")
    object Opening : CameraState("Opening")
    object Opened : CameraState("Opened")
    object Closing : CameraState("Closing")
    object Closed : CameraState("Closed")
    data class Error(val message: String, val throwable: Throwable? = null) : CameraState("Error: $message")
    
    override fun toString(): String = name
}

data class CameraParameters(
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val focusDistance: Float? = null,
    val whiteBalanceMode: Int? = null,
    val colorCorrectionGain: ColorCorrectionGains? = null,
    val colorCorrectionTransform: ColorCorrectionTransform? = null
)

data class ColorCorrectionGains(val r: Float, val gEven: Float, val gOdd: Float, val b: Float)
data class ColorCorrectionTransform(val elements: IntArray) // 3x3 matrix in rational representation
