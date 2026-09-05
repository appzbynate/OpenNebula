package org.opennebula.air.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF0B1020)
val Surface = Color(0xFF151B30)
val OnSurface = Color(0xFFE8ECFF)
val Accent = Color(0xFF7C5CFF)
val Teal = Color(0xFF5CE1C5)
val LogText = Color(0xFFC5D0F0)

val OpenNebulaColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Teal,
    background = Background,
    surface = Surface,
    onBackground = OnSurface,
    onSurface = OnSurface,
)
