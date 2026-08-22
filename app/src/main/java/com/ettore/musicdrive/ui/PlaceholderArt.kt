package com.ettore.musicdrive.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** Placeholder art (album/artist tiles) until automatic covers are resolved: a colored tile keyed by name. */
private val placeholderPalette = listOf(
    0xFF6750A4, 0xFF7D5260, 0xFF386A20, 0xFF984061, 0xFF31628E, 0xFF8C4A2F,
)

fun placeholderColorFor(name: String): Color {
    val hash = name.hashCode()
    val index = (if (hash == Int.MIN_VALUE) 0 else abs(hash)) % placeholderPalette.size
    return Color(placeholderPalette[index])
}
