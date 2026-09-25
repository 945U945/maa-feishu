package com.aliothmoon.maameow.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.theme.MaaMotion


@Composable
fun FloatBall(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    countdownSeconds: Int? = null,
) {
    val safeCountdownSeconds = countdownSeconds?.takeIf { it > 0 }
    val isCountdown = safeCountdownSeconds != null
    val countdownText = safeCountdownSeconds?.toString().orEmpty()

    val targetColor = when {
        isCountdown -> Color(0xFFFFA726)
        isRunning -> Color(0xFF4CAF50) // 绿色 - 打卡执行中
        else -> MaterialTheme.colorScheme.primary
    }

    val baseColor by animateColorAsState(
        targetValue = targetColor.copy(alpha = 0.85f),
        animationSpec = tween(MaaMotion.Medium),
    )

    val textColor = Color.White

    val infiniteTransition = rememberInfiniteTransition()
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
    )

    val alphaModifier = if (isRunning || isCountdown) {
        Modifier.alpha(breathingAlpha)
    } else {
        Modifier
    }

    val stateDescription = stringResource(
        if (isRunning) R.string.overlay_floatball_state_running
        else R.string.overlay_floatball_state_idle
    )
    val semanticsDescription = if (isCountdown) {
        stringResource(R.string.overlay_floatball_countdown_desc, countdownText)
    } else {
        stateDescription
    }

    Surface(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(1.dp, textColor.copy(alpha = 0.15f), CircleShape)
            .then(alphaModifier)
            .semantics {
                contentDescription = semanticsDescription
            },
        shape = CircleShape,
        color = baseColor,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isCountdown) {
                Text(
                    text = countdownText,
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            } else {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.PlayArrow else Icons.Filled.Check,
                    contentDescription = stateDescription,
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
