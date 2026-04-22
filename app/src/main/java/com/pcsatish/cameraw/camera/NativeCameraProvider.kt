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

    private val _isoRange = MutableStateFlow(50..3200)
    override val isoRange: StateFlow<IntRange> = _isoRange.asStateFlow()

    private val _exposureRange = MutableStateFlow(100_000L..1_000_000_000L)
    override val exposureRange: StateFlow<LongRange> = _exposureRange.asStateFlow()

    private var lastAutoIso = 400
    private var lastAutoExposure = 20_000_000L
    private var maxAnalogSensitivity = 800

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L

    private var diagnosticFrameCount = 0
    private var lumaLogFrameCount = 0
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val actualBoost = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
            } else 100
            val effectiveIso = (actualIso * (actualBoost / 100f)).toInt()
            val actualExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)

            // Memory: Update last known good values if in Auto mode
            if (aeMode == CaptureRequest.CONTROL_AE_MODE_ON) {
                lastAutoIso = effectiveIso
                if (actualExp > 0) lastAutoExposure = actualExp
            }

            diagnosticFrameCount++
            if (diagnosticFrameCount % 60 == 0) {
                Log.d("HardwareDiagnostics", 
                    "AE_MODE: $aeMode | UI_REQ: ${currentParameters.iso ?: "AUTO"} | ACT_TOTAL_ISO: $effectiveIso (Ana:$actualIso, Bst:$actualBoost) | ACT_EXP: ${actualExp / 1_000_000}ms")
            }
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
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
                    
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                        // Artificially extend range to 3200 to allow Digital Boost in UI
                        _isoRange.value = it.lower..3200
                        Log.d("HardwareDiagnostics", "Hardware ISO Range: ${it.lower} - ${it.upper}, Extended to 3200")
                    }
                    characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)?.let {
                        maxAnalogSensitivity = it
                        Log.d("HardwareDiagnostics", "Max Analog ISO: $maxAnalogSensitivity")
                    } ?: run {
                        Log.d("HardwareDiagnostics", "Max Analog ISO not found, defaulting to 800")
                    }

                    characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                        _exposureRange.value = it.lower..it.upper
                        Log.d("HardwareDiagnostics", "Exposure Range: ${it.lower} - ${it.upper} ns")
                    }

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
            val characteristics = cameraManager.getCameraCharacteristics(device.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val nativeRatio = if (activeArraySize != null) {
                activeArraySize.width().toFloat() / activeArraySize.height().toFloat()
            } else {
                CameraConstants.DEFAULT_ASPECT_RATIO
            }

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

            _previewSize.value = previewSize

            val stillSize = map?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { 
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    Math.abs(ratio - nativeRatio) < CameraConstants.ASPECT_RATIO_TOLERANCE 
                }
                ?.maxByOrNull { it.width * it.height }
                ?: Size(previewSize.width, previewSize.height)
            
            imageReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2)
            
            val yuvChoices = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val yuvSize = yuvChoices
                .filter { it.width <= 640 && it.height <= 480 }
                .maxByOrNull { it.width * it.height }
                ?: Size(640, 480)

            yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 5).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val remaining = buffer.remaining()
                        
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
                        
                        val calculatedLuma = totalLuma / (data.size.toDouble() / step)
                        _luma.value = calculatedLuma
                        
                        lumaLogFrameCount++
                        if (lumaLogFrameCount % 60 == 0) {
                            Log.d("LumaMonitor", "ISO: ${currentParameters.iso ?: "AUTO"} | LUMA: $calculatedLuma")
                        }

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

            val surfaces = listOf(surface, imageReader!!.surface, yuvReader!!.surface)
            
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // Apply initial parameters
                        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        requestBuilder.addTarget(surface)
                        yuvReader?.surface?.let { requestBuilder.addTarget(it) }
                        
                        // Default to Continuous AF
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        
                        // Apply current manual/auto parameters immediately
                        applyCurrentParameters(requestBuilder)
                        
                        session.setRepeatingRequest(requestBuilder.build(), captureCallback, cameraHandler)
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
            
            // Sync with current manual settings
            applyCurrentParameters(captureBuilder)

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

        // Replace current parameters with new ones. 
        // Note: The UI layer in MainActivity now manages the complete exposure state.
        currentParameters = params

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            yuvReader?.surface?.let { requestBuilder.addTarget(it) }
            
            applyCurrentParameters(requestBuilder)
            
            session.setRepeatingRequest(requestBuilder.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            Log.e("NativeCameraProvider", "Failed to update parameters", e)
            _state.value = CameraState.Error("Parameter update failed: ${e.message}", e)
        }
    }

    private fun applyCurrentParameters(requestBuilder: CaptureRequest.Builder) {
        val isManualAE = currentParameters.exposureTimeNs != null || currentParameters.iso != null
        Log.d("ParamTrace", "Applying params: ManualAE=$isManualAE, ISO=${currentParameters.iso}")
        
        // 1. Exposure & ISO (AE)
        if (isManualAE) {
            // CRITICAL for LG-H930: CONTROL_MODE must be AUTO to keep ISP active, 
            // but AE_MODE is OFF to allow manual SENSOR_* overrides.
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            
            // Sensitivity (ISO)
            // Use requested ISO, or fallback to last known auto ISO
            val isoToSet = currentParameters.iso ?: lastAutoIso
            
            if (isoToSet > maxAnalogSensitivity) {
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, maxAnalogSensitivity)
                val boost = (isoToSet.toFloat() / maxAnalogSensitivity.toFloat() * 100f).toInt()
                requestBuilder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
            } else {
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoToSet)
                requestBuilder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
            }
            
            // Exposure Time
            // Use requested Exposure, or fallback to last known auto exposure
            val expTimeToSet = currentParameters.exposureTimeNs ?: lastAutoExposure
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expTimeToSet)
            
            // Bug 6 Fix: Frame duration must always accompany manual SENSOR_* settings
            val frameDuration = Math.max(expTimeToSet, 33_333_333L)
            requestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // 2. Focus (AF)
        currentParameters.focusDistance?.let {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        } ?: run {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // 3. White Balance (AWB)
        currentParameters.whiteBalanceMode?.let { mode ->
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, mode)
            if (mode == CaptureRequest.CONTROL_AWB_MODE_OFF) {
                currentParameters.colorCorrectionGain?.let { gains ->
                    requestBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    requestBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains.r, gains.gEven, gains.gOdd, gains.b))
                }
                currentParameters.colorCorrectionTransform?.let { transform ->
                    requestBuilder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(transform.elements))
                }
            }
        }
    }
}
