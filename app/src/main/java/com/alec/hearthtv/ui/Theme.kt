package com.alec.hearthtv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ember = Color(0xFFFF7A1A)
val EmberDim = Color(0xFF7A3A0E)
val Ink = Color(0xFF0E0E11)
val Slate = Color(0xFF1A1B22)
val SlateLight = Color(0xFF262833)
val Paper = Color(0xFFF2EDE4)
val PaperDim = Color(0xFFA9A39A)
val Good = Color(0xFF3DBE7A)
val Bad = Color(0xFFE05A4F)

@Composable
fun HearthTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ember,
            onPrimary = Ink,
            primaryContainer = EmberDim,
            onPrimaryContainer = Paper,
            secondary = PaperDim,
            background = Ink,
            onBackground = Paper,
            surface = Ink,
            onSurface = Paper,
            surfaceVariant = Slate,
            onSurfaceVariant = PaperDim,
            surfaceContainer = Slate,
            surfaceContainerHigh = SlateLight,
            error = Bad,
            outline = SlateLight,
        ),
        content = content,
    )
}
