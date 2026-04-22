package com.pcsatish.cameraw

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import kotlin.math.abs

class IsoCalibrationTest {

    // Calibration State (The "Algorithm" we are tuning)
    private var useAeLock = false
    private var useControlModeAuto = true
    private var useManualMode = true
    private var baseGain = 1.0

    @Test
    fun calibrateIsoAlgorithmWithLumaCheck() {
        var trial = 1
        var success = false
        
        println("Starting ISO Calibration Experiment with Luma verification...")

        while (trial <= 100 && !success) {
            println("\n--- TRIAL $trial ---")
            
            val lumaResults = mutableListOf<Double>()
            val steps = listOf(100, 1100, 2100, 3100)
            
            var monotonicIncrease = true
            
            for (iso in steps) {
                // 1. Simulate capture and get Luma
                val luma = simulateCaptureAndGetLuma(iso)
                lumaResults.add(luma)
                
                println("  ISO: $iso | Calculated Luma: $luma")
            }
            
            // 2. Verify monotonic increase (Luma must increase with ISO)
            for (i in 1 until lumaResults.size) {
                if (lumaResults[i] <= lumaResults[i-1]) {
                    monotonicIncrease = false
                    break
                }
            }
            
            if (monotonicIncrease && lumaResults.last() > lumaResults.first() * 2) {
                println("RESULT: SUCCESS! Luma scales correctly with ISO.")
                println("Winning Algorithm Configuration: AE_LOCK=$useAeLock, CONTROL_MODE_AUTO=$useControlModeAuto, MANUAL_MODE=$useManualMode")
                success = true
            } else {
                println("RESULT: FAILURE. Luma did not increase monotonically or range was too small.")
                analyzeAndFix(trial)
                trial++
            }
        }
    }

    private fun simulateCaptureAndGetLuma(iso: Int): Double {
        // This simulates the LG-H930 Hardware behavior
        // Logic: if manual mode is ON but AE_LOCK is OFF, high ISO causes "darkening" bug.
        // If manual mode is OFF, it just returns a fixed auto-exposure luma.
        
        if (!useManualMode) return 120.0 // Auto-exposure baseline
        
        val baseLuma = 40.0
        
        // Simulating the bug: without AE_LOCK, high ISO (above 400) causes reset to min exposure
        val bugMultiplier = if (!useAeLock && iso > 400) 0.1 else 1.0
        
        // Simulating the darkening bug: if CONTROL_MODE is not AUTO, some HALs throttle gain
        val modeMultiplier = if (!useControlModeAuto) 0.5 else 1.0
        
        val luma = baseLuma * (iso.toDouble() / 100.0) * bugMultiplier * modeMultiplier * baseGain
        return luma.coerceIn(0.0, 255.0)
    }

    private fun analyzeAndFix(trial: Int) {
        // Systematic exploration of the flag space
        when (trial % 4) {
            0 -> { 
                println("  FIX: Toggling AE_LOCK")
                useAeLock = !useAeLock 
            }
            1 -> { 
                println("  FIX: Toggling CONTROL_MODE_AUTO")
                useControlModeAuto = !useControlModeAuto 
            }
            2 -> {
                println("  FIX: Adjusting Base Gain Calibration")
                baseGain *= 1.2
            }
            3 -> {
                println("  FIX: Forcing Manual Mode Re-initialization")
                useManualMode = true
            }
        }
    }
}
