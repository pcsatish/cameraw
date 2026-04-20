package com.pcsatish.cameraw.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.pcsatish.cameraw.camera.CameraConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var lastAppCpuTime = 0L
    private var lastSystemTime = System.currentTimeMillis()

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                _metrics.value = PerformanceMetrics(
                    cpuLoad = calculateAppCpuLoad(),
                    gpuLoad = 0.0,
                    memoryUsageMb = getMemoryUsage(),
                    thermalStatus = getThermalStatus()
                )
                delay(CameraConstants.PERFORMANCE_MONITOR_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
    }

    private fun calculateAppCpuLoad(): Double {
        return try {
            // Read process-specific stats (utime + stime)
            val reader = RandomAccessFile("/proc/self/stat", "r")
            val line = reader.readLine() ?: return 0.0
            reader.close()

            val parts = line.split(" ")
            // utime is index 13, stime is index 14
            val utime = parts[13].toLong()
            val stime = parts[14].toLong()
            val totalAppTime = utime + stime

            val now = System.currentTimeMillis()
            val timeDiff = now - lastSystemTime
            
            // LG-H930 (Snapdragon 835) has 8 cores
            val numCores = Runtime.getRuntime().availableProcessors()
            
            // This is a rough approximation of process load %
            val cpuLoad = if (lastAppCpuTime > 0 && timeDiff > 0) {
                val appTimeDiff = (totalAppTime - lastAppCpuTime) * 10 // Assuming 100Hz jiffies
                (appTimeDiff.toDouble() / timeDiff) * 100.0 / numCores
            } else 0.0

            lastAppCpuTime = totalAppTime
            lastSystemTime = now
            
            Math.min(100.0, cpuLoad)
        } catch (e: Exception) {
            Log.e("PerformanceMonitor", "Failed to read /proc/self/stat: ${e.message}")
            0.0
        }
    }

    private fun getMemoryUsage(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
    }

    private fun getThermalStatus(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                else -> "Other"
            }
        } else "N/A"
    }
}
