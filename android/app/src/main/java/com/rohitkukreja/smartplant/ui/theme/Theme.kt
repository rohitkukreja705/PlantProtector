package com.rohitkukreja.smartplant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Leaf = Color(0xFF2E7D32)
val LeafLight = Color(0xFF81C784)
val Water = Color(0xFF0288D1)
val Sun = Color(0xFFF9A825)
val Danger = Color(0xFFC62828)
val Warn = Color(0xFFEF6C00)
val Ok = Color(0xFF2E7D32)

private val Light = lightColorScheme(
    primary = Leaf,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D0F),
    secondary = Water,
    tertiary = Sun,
    background = Color(0xFFF6F8F2),
    surface = Color(0xFFF6F8F2),
    surfaceVariant = Color(0xFFE6EDE1),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = LeafLight,
    onPrimary = Color(0xFF0B3D0F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF4FC3F7),
    tertiary = Color(0xFFFFD54F),
    background = Color(0xFF111511),
    surface = Color(0xFF111511),
    surfaceVariant = Color(0xFF263026),
    error = Color(0xFFEF9A9A),
)

@Composable
fun SmartPlantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
