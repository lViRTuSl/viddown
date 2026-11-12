package com.viddown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Sabit koyu tema (masaüstü sürümüyle aynı renk dili). Sistem temasından/dinamik renkten etkilenmez. */
private val YtScheme = darkColorScheme(
    primary = Color(0xFFEF5350),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A1E1E),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF64B5F6),
    onSecondary = Color(0xFF00263F),
    background = Color(0xFF121214),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2A30),
    onSurfaceVariant = Color(0xFFB8B8C0),
    surfaceContainer = Color(0xFF1F1F24),
    surfaceContainerHigh = Color(0xFF26262B),
    outline = Color(0xFF55555E),
    error = Color(0xFFEF5350),
)

@Composable
fun YtTheme(content: @Composable () -> Unit) {
    // Yazı boyutu sistem ayarından bağımsız sabit: tasarlanan düzen bozulmaz.
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1f)) {
        MaterialTheme(colorScheme = YtScheme, content = content)
    }
}

fun statusColor(s: Status): Color = when (s) {
    Status.READY -> Color(0xFF4DD0E1)
    Status.WAITING -> Color(0xFFBDBDBD)
    Status.DOWNLOADING -> Color(0xFF64B5F6)
    Status.CONVERTING -> Color(0xFFBA68C8)
    Status.DONE -> Color(0xFF66BB6A)
    Status.ERROR -> Color(0xFFEF5350)
    Status.STOPPED -> Color(0xFFFFA726)
}
