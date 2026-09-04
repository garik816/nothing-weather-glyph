package com.nothinglondon.sdkdemo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NothingColorScheme = darkColorScheme(
    primary = NothingGreen,
    onPrimary = NothingGreenDark,
    primaryContainer = NothingGreenDark,
    onPrimaryContainer = NothingGreen,
    secondary = NothingWhite,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingWhite,
    surfaceVariant = NothingSurfaceHigh,
    onSurfaceVariant = NothingMuted,
    outline = NothingMuted
)

private val NothingShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp)
)

@Composable
fun NothingAndroidSDKDemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NothingColorScheme,
        typography = Typography,
        shapes = NothingShapes,
        content = content
    )
}
