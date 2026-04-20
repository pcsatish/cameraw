package com.pcsatish.cameraw.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@Singleton
class NativeCameraProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CameraProvider {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
    private val _state = MutableStateFlow<CameraState>(CameraState.Initial)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _previewSize = MutableStateFlow<Size?>(null)
    override val previewSize: StateFlow<Size?> = _previewSize.asStateFlow()

    private val _sensorOrientation = MutableStateFlow(0)
    override val sensorOrientation: StateFlow<Int> = _sensorOrientation.asStateFlow()

    private val _luma = MutableStateFlow(0.0)
    override val luma: StateFlow<Double> = _luma.asStateFlow()

    private val _fps = MutableStateFlow(0)
    override val fps: StateFlow<Int> = _fps.asStateFlow()

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Background threads for heavy processing
    private var cameraThread: android.os.HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var processingThread: android.os.HandlerThread? = null
    private var processingHandler: Handler? = null

    init {
        startBackgroundThreads()
    }

    private fun startBackgroundThreads() {
        cameraThread = android.os.HandlerThread("CameraBackground").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        processingThread = android.os.HandlerThread("FrameProcessing").apply { start() }
        processingHandler = Handler(processingThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    override suspend fun open(cameraId: String) = suspendCancellableCoroutine<Unit> { cont ->
        _state.value = CameraState.Opening
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    _sensorOrientation.value = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    logSupportedResolutions(cameraId)
                    _state.value = CameraState.Opened
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _state.value = CameraState.Closed
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    val msg = "Camera device error: $error"
                    _state.value = CameraState.Error(msg)
                    if (cont.isActive) cont.resumeWithException(Exception(msg))
                }
            }, cameraHandler)
        } catch (e: Exception) {
            _state.value = CameraState.Error("Failed to open camera: ${e.message}", e)
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private fun logSupportedResolutions(cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            
            Log.d("CameraCalibration", "--- Camera $cameraId Calibration Data ---")
            Log.d("CameraCalibration", "Sensor Raw Pixel Array: $sensorSize")
            
            map?.getOutputSizes(SurfaceTexture::class.java)?.forEach { 
                Log.d("CameraCalibration", "Supported Preview Size: ${it.width}x${it.height} (Ratio: ${it.width.toFloat()/it.height})")
            }
            
            map?.getOutputSizes(ImageFormat.YUV_420_888)?.forEach {
                Log.d("CameraCalibration", "Supported YUV Size: ${it.width}x${it.height}")
            }
        } catch (e: Exception) {
            Log.e("CameraCalibration", "Failed to log resolutions", e)
        }
    }

    private var yuvReader: ImageReader? = null
    private var lumaBuffer: ByteArray? = null

    override suspend fun startPreview(surface: Surface) = suspendCancellableCoroutine<Unit> { cont ->
        val device = cameraDevice
        if (device == null) {
            _state.value = CameraState.Error("Camera not ready for preview")
            if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera not opened"))
            return@suspendCancellableCoroutine
        }
        previewSurface = surface

        try {
            previewSurface = surface
            val characteristics = cameraManager.getCameraCharacteristics(device.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            // Get the pixel array size to determine native aspect ratio
            val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val nativeRatio = if (activeArraySize != null) {
                activeArraySize.width().toFloat() / activeArraySize.height().toFloat()
            } else {
                // Fallback to a common ratio if not found
                CameraConstants.DEFAULT_ASPECT_RATIO
            }

            Log.d("CameraCalibration", "Sensor Native Aspect Ratio: $nativeRatio")

            // LG-H930 Optimization: Strictly prefer native aspect ratio (usually 4:3) 
            // and cap at 1920px to avoid ISP lag/bypass issues on Snapdragon 835.
            val choices = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            
            val previewSize = choices
                .filter { 
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    Math.abs(ratio - nativeRatio) < CameraConstants.ASPECT_RATIO_TOLERANCE || 
                    Math.abs(ratio - (1f / nativeRatio)) < CameraConstants.ASPECT_RATIO_TOLERANCE 
                }
                .filter { it.width <= CameraConstants.MAX_PREVIEW_DIMENSION && it.height <= CameraConstants.MAX_PREVIEW_DIMENSION }
                .maxByOrNull { it.width * it.height }
                ?: choices.firstOrNull() ?: CameraConstants.FALLBACK_PREVIEW_SIZE

            Log.d("CameraCalibration", "Selected Preview Size: ${previewSize.width}x${previewSize.height}")
            _previewSize.value = previewSize

            // ImageReader for Still Capture - use largest available for native ratio
            val stillSize = map?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { 
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    Math.abs(ratio - nativeRatio) < CameraConstants.ASPECT_RATIO_TOLERANCE 
                }
                ?.maxByOrNull { it.width * it.height }
                ?: Size(previewSize.width, previewSize.height)
            
            imageReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2)
            
            // ImageReader for YUV - Use a smaller fixed size for telemetry to save bandwidth
            val yuvChoices = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val yuvSize = yuvChoices
                .filter { it.width <= 640 && it.height <= 480 }
                .maxByOrNull { it.width * it.height }
                ?: Size(640, 480)

            Log.d("CameraCalibration", "Selected Telemetry (YUV) Size: ${yuvSize.width}x${yuvSize.height}")

            yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 5).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val remaining = buffer.remaining()
                        
                        // Reuse buffer to eliminate GC pressure
                        if (lumaBuffer == null || lumaBuffer!!.size != remaining) {
                            lumaBuffer = ByteArray(remaining)
                        }
                        buffer.get(lumaBuffer!!)
                        
                        var totalLuma = 0.0
                        val step = CameraConstants.LUMA_SAMPLING_STEP
                        val data = lumaBuffer!!
                        
                        for (i in data.indices step step) {
                            totalLuma += (data[i].toInt() and 0xFF)
                        }
                        
                        _luma.value = totalLuma / (data.size.toDouble() / step)

                        // Calculate FPS
                        val now = System.currentTimeMillis()
                        frameCount++
                        if (now - lastFpsUpdateTime >= CameraConstants.FPS_UPDATE_INTERVAL_MS) {
                            _fps.value = frameCount
                            frameCount = 0
                            lastFpsUpdateTime = now
                        }
                    } finally {
                        image.close()
                    }
                }, processingHandler)
            }

            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)
            previewRequestBuilder.addTarget(yuvReader!!.surface)

            val surfaces = listOf(surface, imageReader!!.surface, yuvReader!!.surface)
            
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
                        if (cont.isActive) cont.resume(Unit)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(Exception("Failed to configure capture session"))
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) }
                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    stateCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, stateCallback, mainHandler)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    override suspend fun close() {
        _state.value = CameraState.Closing
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        yuvReader?.close()
        yuvReader = null
        
        stopBackgroundThreads()
        _state.value = CameraState.Closed
    }

    private fun stopBackgroundThreads() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e("NativeCameraProvider", "Error stopping camera thread", e)
        }

        processingThread?.quitSafely()
        try {
            processingThread?.join()
            processingThread = null
            processingHandler = null
        } catch (e: InterruptedException) {
            Log.e("NativeCameraProvider", "Error stopping processing thread", e)
        }
    }

    override suspend fun captureStill(onCaptureStarted: () -> Unit) {
        val session = captureSession
        val device = cameraDevice
        val reader = imageReader
        if (session == null || device == null || reader == null) return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            
            // Apply current manual parameters to the still capture
            currentParameters.exposureTimeNs?.let {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            currentParameters.iso?.let {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            currentParameters.focusDistance?.let {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                saveImage(bytes)
                image.close()
            }, mainHandler)

            onCaptureStarted()
            session.capture(captureBuilder.build(), null, mainHandler)
        } catch (e: Exception) {
            _state.value = CameraState.Error("Capture failed: ${e.message}", e)
        }
    }

    private fun saveImage(bytes: ByteArray) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(context.getExternalFilesDir(null), "Cameraw")
        if (!storageDir.exists()) storageDir.mkdirs()
        
        val file = File(storageDir, "IMG_$timeStamp.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        Log.d("NativeCameraProvider", "Image saved: ${file.absolutePath}")
    }

    private var currentParameters = CameraParameters()

    override suspend fun updateParameters(params: CameraParameters) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

        // Merge new parameters with current ones
        currentParameters = currentParameters.copy(
            exposureTimeNs = params.exposureTimeNs ?: currentParameters.exposureTimeNs,
            iso = params.iso ?: currentParameters.iso,
            focusDistance = params.focusDistance ?: currentParameters.focusDistance,
            whiteBalanceMode = params.whiteBalanceMode ?: currentParameters.whiteBalanceMode,
            colorCorrectionGain = params.colorCorrectionGain ?: currentParameters.colorCorrectionGain,
            colorCorrectionTransform = params.colorCorrectionTransform ?: currentParameters.colorCorrectionTransform
        )

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            
            currentParameters.exposureTimeNs?.let {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            currentParameters.iso?.let {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            currentParameters.focusDistance?.let {
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            currentParameters.whiteBalanceMode?.let {
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, it)
            if (it == CaptureRequest.CONTROL_AWB_MODE_OFF) {
                currentParameters.colorCorrectionGain?.let { gains ->
                    requestBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    requestBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains.r, gains.gEven, gains.gOdd, gains.b))
                }
                currentParameters.colorCorrectionTransform?.let { transform ->
                    requestBuilder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(transform.elements))
                }
            }
        }
            
            session.setRepeatingRequest(requestBuilder.build(), null, mainHandler)
        } catch (e: Exception) {
            _state.value = CameraState.Error("Parameter update failed: ${e.message}", e)
        }
    }
}
