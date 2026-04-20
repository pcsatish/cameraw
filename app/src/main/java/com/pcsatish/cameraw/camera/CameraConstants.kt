package com.pcsatish.cameraw.camera

import android.util.Size

object CameraConstants {
    // Aspect Ratio and Resolution
    const val DEFAULT_ASPECT_RATIO = 1.3333f // 4:3
    const val ASPECT_RATIO_TOLERANCE = 0.01f
    const val MAX_PREVIEW_DIMENSION = 1920
    val FALLBACK_PREVIEW_SIZE = Size(1280, 720)

    // Telemetry and Processing
    const val LUMA_SAMPLING_STEP = 32
    const val FPS_UPDATE_INTERVAL_MS = 1000L
    const val PERFORMANCE_MONITOR_INTERVAL_MS = 2000L

    // Manual Control Ranges
    const val MIN_ISO = 100f
    const val MAX_ISO = 3200f
    const val DEFAULT_ISO = 400f

    const val MIN_EXPOSURE_NS = 100_000L
    const val MAX_EXPOSURE_NS = 100_000_000L
    const val DEFAULT_EXPOSURE_NS = 20_000_000L

    const val MIN_FOCUS_DISTANCE = 0f
    const val MAX_FOCUS_DISTANCE = 10f
    const val DEFAULT_FOCUS_DISTANCE = 0f

    // Manual White Balance Ranges
    const val MIN_CCT = 2000
    const val MAX_CCT = 10000
    const val DEFAULT_CCT = 5000
}
