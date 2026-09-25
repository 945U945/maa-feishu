package com.aliothmoon.maameow.presentation.view.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.domain.launch.LaunchSession
import org.koin.compose.koinInject
import com.aliothmoon.maameow.domain.launch.LaunchPipeline

/**
 * 悬浮控制面板（打卡版）。
 *
 * 原工程的 ExpandedControlPanel 是一个多页游戏控制台（任务列表、仓库识别、
 * 抄作业、小工具等），打卡版全部剔除，只保留：
 * - 当前状态（待机 / 倒计时 / 打卡中）
 * - 关闭面板、回到应用、锁定面板位置
 */
@Composable
fun ExpandedControlPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onHome: () -> Unit = {},
    isLocked: Boolean = false,
    onLockToggle: (Boolean) -> Unit = {},
) {
    val pipeline: LaunchPipeline = koinInject()
    val session by pipeline.session.collectAsStateWithLifecycle()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题栏：状态 + 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.overlay_panel_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onLockToggle(!isLocked) }) {
                    Icon(
                        imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = stringResource(
                            if (isLocked) R.string.overlay_panel_unlock
                            else R.string.overlay_panel_lock
                        ),
                    )
                }
                IconButton(onClick = onHome) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = stringResource(R.string.overlay_panel_home),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.overlay_panel_close),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 当前状态
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.overlay_panel_status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = session.toStatusText(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun LaunchSession.toStatusText(): String = when (this) {
    is LaunchSession.Idle -> stringResource(R.string.overlay_panel_status_idle)
    is LaunchSession.InFlight -> when (val p = phase) {
        is LaunchSession.Phase.DevicePrep -> stringResource(R.string.overlay_panel_status_prep)
        is LaunchSession.Phase.Counting ->
            stringResource(R.string.overlay_panel_status_counting, p.remainingSeconds)

        is LaunchSession.Phase.Preparing -> stringResource(R.string.overlay_panel_status_preparing)
        is LaunchSession.Phase.Starting -> stringResource(R.string.overlay_panel_status_starting)
    }
}
