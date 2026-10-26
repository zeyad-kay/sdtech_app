package com.example.sdtech_android_native.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdtech_android_native.pairing.WifiConnectionState

private val ConnectedColor = Color(0xFF2ECC71)
private val ConnectingColor = Color(0xFFF1C40F)
private val DisconnectedColor = Color(0xFFE74C3C)

/** Small dot + label showing the paired WiFi connection's live state: green/solid when connected, yellow/blinking while (re)connecting, red when disconnected. */
@Composable
fun ConnectionStatusIndicator(state: WifiConnectionState, modifier: Modifier = Modifier) {
    val isConnecting = state is WifiConnectionState.Connecting
    val color = when (state) {
        is WifiConnectionState.Connected -> ConnectedColor
        is WifiConnectionState.Connecting -> ConnectingColor
        is WifiConnectionState.Idle, is WifiConnectionState.Disconnected -> DisconnectedColor
    }
    val label = when (state) {
        is WifiConnectionState.Connected -> "Connected"
        is WifiConnectionState.Connecting -> "Connecting"
        is WifiConnectionState.Idle, is WifiConnectionState.Disconnected -> "Disconnected"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "connection-status-blink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connection-status-blink-alpha",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (isConnecting) blinkAlpha else 1f)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
