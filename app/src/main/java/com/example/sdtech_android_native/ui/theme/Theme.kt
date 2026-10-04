package com.example.sdtech_android_native.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DeepPurple = Color(0xFF673AB7)
val DeepPurpleAccent = Color(0xFF7C4DFF)
val DeepPurple200 = Color(0xFFB39DDB)
val DeepPurple300 = Color(0xFF9575CD)
val DeepPurple400 = Color(0xFF7E57C2)
val DeepPurple900 = Color(0xFF311B92)
val RedAccent = Color(0xFFFF5252)
val Red = Color(0xFFF44336)
val Red900 = Color(0xFFB71C1C)
val SurfaceContainerHigh = Color(0xFF2B2930)
val BackgroundDark = Color(0xFF141218)

private val DarkColors = darkColorScheme(
    primary = DeepPurpleAccent,
    onPrimary = Color.White,
    secondary = DeepPurple200,
    surface = BackgroundDark,
    background = BackgroundDark,
    surfaceContainerHigh = SurfaceContainerHigh,
    error = Red,
)

@Composable
fun SdtechAndroidNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
