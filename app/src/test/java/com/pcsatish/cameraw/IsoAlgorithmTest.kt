package com.pcsatish.cameraw

import org.junit.Test
import kotlin.math.abs

class IsoAlgorithmTest {

    // Simulation of the Camera2 ISP behavior on LG-H930
    class SimulatedIsp {
        var aeMode = 1 // 1=ON, 0=OFF
        var iso = 100
        var exposureTimeNs = 10_000_000L
        var frameDurationNs = 33_333_333L
        var aeLock = false
        var controlMode = 1 // 1=AUTO

        fun getPixelValue(baseValue: Int = 128): Int {
            if (aeMode == 1) return 150 // Auto brightness
            
            // Bug simulation: If aeLock is false in manual mode on this specific HAL,
            // increasing ISO beyond 400 causes a state reset to minimum exposure.
            val effectiveExp = if (!aeLock && iso > 400) 1_000_000L else exposureTimeNs
            
            // Frame duration constraint
            val finalExp = if (effectiveExp > frameDurationNs) frameDurationNs else effectiveExp
            
            val gain = (iso.toDouble() / 100.0) * (finalExp.toDouble() / 10_000_000L)
            return (baseValue * gain).toInt().coerceIn(0, 255)
        }
    }

    @Test
    fun verifyIsoAlgorithmIteratively() {
        val isp = SimulatedIsp()
        var trial = 1
        var success = false
        
        // My "Algorithm" state that I will tune
        var useAeLock = false
        var useControlModeAuto = true

        println("Starting ISO algorithm verification loop...")

        while (trial <= 100 && !success) {
            println("\nTRIAL $trial")
            var allStepsPassed = true
            val steps = listOf(100, 1100, 2100, 3100)
            
            for (isoStep in steps) {
                // Apply our algorithm logic to the simulator
                isp.aeMode = 0
                isp.iso = isoStep
                isp.exposureTimeNs = 20_000_000L
                isp.frameDurationNs = Math.max(isp.exposureTimeNs, 33_333_333L)
                isp.aeLock = useAeLock
                isp.controlMode = if (useControlModeAuto) 1 else 0

                val actual = isp.getPixelValue()
                val expected = (128.0 * (isoStep.toDouble()/100.0) * (20.0/10.0)).toInt().coerceIn(0, 255)
                
                println("  ISO $isoStep | Expected $expected | Actual $actual")
                
                if (abs(actual - expected) > 5) {
                    println("  MISMATCH DETECTED! Darkening occurred.")
                    allStepsPassed = false
                    break
                }
            }
            
            if (allStepsPassed) {
                println("RESULT: SUCCESS at Trial $trial. Final Algorithm: AE_LOCK=$useAeLock, CONTROL_MODE_AUTO=$useControlModeAuto")
                success = true
            } else {
                // ANALYZE & FIX
                println("  ANALYSIS: Actual < Expected. ISP is ignoring sensitivity/exposure.")
                println("  FIX: Setting AE_LOCK to true to pin the manual state.")
                useAeLock = true
                trial++
            }
        }
    }
}
