package com.pcsatish.cameraw.monitoring

data class PerformanceMetrics(
    val cpuLoad: Double = 0.0,
    val gpuLoad: Double = 0.0,
    val memoryUsageMb: Long = 0,
    val thermalStatus: String = "Normal"
)
