package com.aliothmoon.maameow.presentation.view.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maameow.presentation.components.SettingRow
import com.aliothmoon.maameow.presentation.viewmodel.HomeViewModel

/**
 * 首页。
 *
 * 信息层级设计（按用户关心程度排序）：
 * 1. 上次打卡结果 —— 用户最想立刻知道"打下卡了吗"
 * 2. 下次打卡时间 —— 其次关心"什么时候还会打"
 * 3. 运行前置条件 —— 无障碍/目标应用状态，异常时才需要用户处理
 * 4. 立即打卡按钮 —— 手动补救入口
 *
 * 刻意不把"权限状态"放最上面：正常运行时它就是正常的，
 * 占着首要位置反而干扰用户看真正关心的打卡结果。
 */
@Composable
fun HomeView(
    viewModel: HomeViewModel,
    onNavigateToProbe: () -> Unit = {},
    onNavigateToProfiles: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次回到首页刷新一次：用户可能刚去系统设置开了无障碍权限
    LaunchedEffect(Unit) {
        viewModel.refreshRuntimeStatus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 上次结果卡片 ──
        LastResultCard(
            hasResult = state.lastResult.isNotBlank(),
            success = state.lastResultSuccess,
            message = state.lastResult,
        )

        // ── 下次打卡 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "下次打卡",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.nextTriggerLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ── 立即打卡 ──
        Button(
            onClick = { viewModel.runCheckInNow() },
            enabled = !state.running && state.accessibilityReady && state.profiles.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("打卡中…")
            } else {
                Text("立即打卡", fontSize = 16.sp)
            }
        }

        // ── 前置条件检查 ──
        Text(
            text = "运行条件",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = "无障碍服务",
                    description = if (state.accessibilityReady) {
                        "已开启，可以读取界面控件"
                    } else {
                        "未开启。这是打卡的必要条件——应用需要它来识别并点击打卡按钮"
                    },
                    titleColor = if (state.accessibilityReady) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                SettingRow(
                    title = "目标应用",
                    description = if (state.targetInstalled) {
                        "已安装"
                    } else {
                        "未检测到安装，请在「打卡方案」中确认包名是否正确"
                    },
                    titleColor = if (state.targetInstalled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        // ── 快捷入口 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "无法打卡？先采集界面信息",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "飞书禁止截屏，所以需要「控件探针」导出界面结构，" +
                            "据此告诉应用打卡按钮在哪。这是配置打卡规则的标准流程。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onNavigateToProbe) {
                    Text("打开控件探针")
                }
            }
        }
    }
}

/**
 * 上次打卡结果卡片。
 *
 * 无记录时展示引导文案而非留白，避免用户困惑"为什么什么都没有"。
 */
@Composable
private fun LastResultCard(
    hasResult: Boolean,
    success: Boolean,
    message: String,
) {
    val containerColor = when {
        !hasResult -> MaterialTheme.colorScheme.surfaceVariant
        success -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val iconTint = when {
        !hasResult -> MaterialTheme.colorScheme.onSurfaceVariant
        success -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    !hasResult -> Icons.Default.Schedule
                    success -> Icons.Default.CheckCircle
                    else -> Icons.Default.Error
                },
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = when {
                        !hasResult -> "还没有打卡记录"
                        success -> "上次打卡成功"
                        else -> "上次打卡失败"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (hasResult && message.isNotBlank()) {
                        message
                    } else {
                        "设置定时任务后，打卡结果会显示在这里"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
