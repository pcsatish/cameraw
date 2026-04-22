package com.pcsatish.cameraw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Preview(showBackground = true, widthDp = 800, heightDp = 400, backgroundColor = 0xFF000000)
@Composable
fun CameraControlOverlayPreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
            CameraSidebar(modifier = Modifier.align(Alignment.CenterStart))
            
            ModeSelectorRow(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                selectedMode = ControlMode.ISO,
                onModeClick = {},
                iso = 400,
                exposureNs = 20_000_000L,
                wbKelvin = 5000,
                focusDistance = 0f
            )

            ControlRuler(
                modifier = Modifier.align(Alignment.CenterEnd),
                selectedMode = ControlMode.ISO,
                iso = 400,
                onIsoChange = {},
                exposureNs = 20_000_000L,
                onExposureChange = {},
                wbKelvin = 5000,
                onWbChange = {},
                focusDistance = 0f,
                onFocusChange = {}
            )
            
            // Shutter & Gallery
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                ShutterButton(onClick = {})
                GalleryPreview(onClick = {})
            }
        }
    }
}

@Composable
fun CameraSidebar(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = {}) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.FlashOn, "Flash", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.Timer, "Timer", tint = Color.White) }
        Text("16:9", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        IconButton(onClick = {}) { Icon(Icons.Default.CenterFocusStrong, "Focus", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.BrightnessAuto, "Metering", tint = Color.White) }
    }
}

@Composable
fun ShutterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clickable { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = Color.White, radius = size.minDimension / 2.2f)
            drawCircle(color = Color.White, radius = size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
fun GalleryPreview(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clickable { onClick() }
            .background(Color.DarkGray, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Image, "Gallery", tint = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun ModeSelectorRow(
    modifier: Modifier = Modifier,
    selectedMode: ControlMode?,
    onModeClick: (ControlMode) -> Unit,
    iso: Int,
    exposureNs: Long,
    wbKelvin: Int,
    focusDistance: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlModeItem("WB", "${wbKelvin}K", selectedMode == ControlMode.WB) { onModeClick(ControlMode.WB) }
        ControlModeItem("FOCUS", if (focusDistance == 0f) "AUTO" else "%.1f".format(focusDistance), selectedMode == ControlMode.FOCUS) { onModeClick(ControlMode.FOCUS) }
        ControlModeItem("EV", "0.0", selectedMode == ControlMode.EV) { onModeClick(ControlMode.EV) }
        ControlModeItem("SPEED", "1/${(1_000_000_000L / exposureNs)}", selectedMode == ControlMode.SPEED) { onModeClick(ControlMode.SPEED) }
        ControlModeItem("ISO", "$iso", selectedMode == ControlMode.ISO) { onModeClick(ControlMode.ISO) }
    }
}

@Composable
fun ControlRuler(
    modifier: Modifier = Modifier,
    selectedMode: ControlMode,
    iso: Int,
    isoRange: IntRange = 100..3200,
    onIsoChange: (Int) -> Unit,
    exposureNs: Long,
    exposureRange: LongRange = 100_000L..1_000_000_000L,
    onExposureChange: (Long) -> Unit,
    wbKelvin: Int,
    onWbChange: (Int) -> Unit,
    focusDistance: Float,
    onFocusChange: (Float) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight(0.6f)
            .width(100.dp)
            .background(Color.Black.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        when (selectedMode) {
            ControlMode.ISO -> {
                VerticalRuler(
                    value = iso.toFloat(),
                    range = isoRange.first.toFloat()..isoRange.last.toFloat(),
                    onValueChange = { onIsoChange(it.roundToInt()) },
                    step = 10f,
                    unit = "",
                    isLogarithmic = true
                )
            }
            ControlMode.SPEED -> {
                VerticalRuler(
                    value = exposureNs.toFloat(),
                    range = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                    onValueChange = { onExposureChange(it.toLong()) },
                    step = 100_000f,
                    unit = "ns",
                    isLogarithmic = true
                )
            }
            ControlMode.WB -> {
                VerticalRuler(
                    value = wbKelvin.toFloat(),
                    range = 2000f..10000f,
                    onValueChange = { onWbChange(it.roundToInt()) },
                    step = 100f,
                    unit = "K"
                )
            }
            ControlMode.FOCUS -> {
                VerticalRuler(
                    value = focusDistance,
                    range = 0f..10f,
                    onValueChange = { onFocusChange(it) },
                    step = 0.1f,
                    unit = "m"
                )
            }
            else -> {}
        }

        // Selection indicator (Yellow line)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2
            drawLine(
                color = Color.Yellow,
                start = Offset(size.width - 40f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 4f
            )
        }
    }
}

@Composable
fun ControlModeItem(
    label: String,
    value: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Yellow else Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = if (isSelected) Color.Yellow else Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun VerticalRuler(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    step: Float,
    unit: String,
    isLogarithmic: Boolean = false
) {
    // Helper to map linear drag to logarithmic value
    fun toLinear(v: Float) = if (isLogarithmic) kotlin.math.ln(v) else v
    fun fromLinear(v: Float) = if (isLogarithmic) kotlin.math.exp(v).coerceIn(range) else v

    val linearRange = toLinear(range.start)..toLinear(range.endInclusive)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    if (isLogarithmic) {
                        val currentLinear = toLinear(value)
                        val sensitivity = (linearRange.endInclusive - linearRange.start) / 1000f
                        val newLinear = (currentLinear + delta * sensitivity).coerceIn(linearRange)
                        onValueChange(fromLinear(newLinear))
                    } else {
                        val newValue = (value - delta * (step / 20f)).coerceIn(range.start, range.endInclusive)
                        onValueChange(newValue)
                    }
                }
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2
            
            val pixelsPerStep = 12f // Tighter ticks
            
            // Draw ticks
            for (i in -30..30) {
                val tickValue = ((value / step).roundToInt() + i) * step
                if (tickValue in range) {
                    val y = centerY - (tickValue - value) * (pixelsPerStep / step)
                    
                    val isMajor = tickValue % (step * 5) == 0f
                    val lineLength = if (isMajor) 25f else 12f
                    
                    drawLine(
                        color = if (tickValue == value) Color.Yellow else Color.White.copy(alpha = 0.5f),
                        start = Offset(width - lineLength, y),
                        end = Offset(width, y),
                        strokeWidth = if (isMajor) 3f else 1.5f
                    )
                    
                    if (isMajor) {
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply {
                                color = if (tickValue == value) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                                alpha = if (tickValue == value) 255 else 180
                                textSize = 28f
                                textAlign = android.graphics.Paint.Align.RIGHT
                                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                            }
                            canvas.nativeCanvas.drawText(
                                tickValue.toInt().toString(),
                                width - 35f,
                                y + 10f,
                                paint
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class ControlMode {
    WB, FOCUS, EV, SPEED, ISO
}
