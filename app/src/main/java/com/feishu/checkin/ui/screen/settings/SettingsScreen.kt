package com.feishu.checkin.ui.screen.settings

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.feishu.checkin.R
import com.feishu.checkin.checkin.model.CheckInPlan
import com.feishu.checkin.core.time.AppSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * ## 分组策略
 *
 * 分三组，按「改动频率」由高到低排列：
 *
 * 1. **打卡方案** —— 飞书改版后需要调整，改动频率最高
 * 2. **高级** —— 调优参数，一般设一次就不动
 * 3. **关于** —— 版本信息与控件探针入口
 *
 * 把低频的放下面，避免用户被无关选项干扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onOpenProbe: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlanSection(
                plans = state.plans,
                selectedId = state.settings.planId,
                onSelect = viewModel::setPlan,
            )

            AdvancedSection(
                settings = state.settings,
                onRetryChange = viewModel::setRetryCount,
                onTimeoutChange = viewModel::setStepTimeoutSeconds,
                onVerboseToggle = viewModel::setVerboseLog,
                onReschedule = viewModel::rescheduleAlarm,
            )

            AboutSection(
                version = state.appVersion,
                onOpenProbe = onOpenProbe,
                onReloadPlans = viewModel::reloadPlans,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 方案选择 */
@Composable
private fun PlanSection(
    plans: List<CheckInPlan>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_plan),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_plan_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            plans.forEach { plan ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = plan.id == selectedId,
                            onClick = { onSelect(plan.id) },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = plan.id == selectedId,
                        onClick = { onSelect(plan.id) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = plan.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "共 ${plan.steps.size} 个步骤" +
                                if (plan.builtIn) " · 内置" else " · 自定义",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 高级设置 */
@Composable
private fun AdvancedSection(
    settings: AppSettings,
    onRetryChange: (Int) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onVerboseToggle: (Boolean) -> Unit,
    onReschedule: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_advanced),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(12.dp))

            // 重试次数
            SliderRow(
                label = stringResource(R.string.settings_retry),
                desc = stringResource(R.string.settings_retry_desc),
                value = settings.retryCount,
                range = 0..3,
                display = "${settings.retryCount} 次",
                onValueChange = onRetryChange,
            )

            Spacer(Modifier.height(8.dp))

            // 单步超时
            SliderRow(
                label = stringResource(R.string.settings_timeout),
                desc = null,
                // 滑块用 1..6 档映射到 4..24 秒，步长 4 秒
                value = ((settings.stepTimeoutMs / 1000L).toInt() - 4) / 4,
                range = 0..5,
                display = "${settings.stepTimeoutMs / 1000} 秒",
                onValueChange = { onTimeoutChange(4 + it * 4) },
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // 详细日志
            SwitchRow(
                title = stringResource(R.string.settings_debug_log),
                desc = stringResource(R.string.settings_debug_log_desc),
                checked = settings.verboseLog,
                onCheckedChange = onVerboseToggle,
            )

            Spacer(Modifier.height(4.dp))

            // 手动重排（排障用）
            TextButton(onClick = onReschedule) {
                Text("重新排定闹钟")
            }
        }
    }
}

/** 关于 */
@Composable
private fun AboutSection(
    version: String,
    onOpenProbe: () -> Unit,
    onReloadPlans: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // 控件探针
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_probe),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_probe_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenProbe) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 重新扫描自定义方案
            TextButton(onClick = onReloadPlans) {
                Text("重新加载自定义方案")
            }
        }
    }
}

/** 滑块行 */
@Composable
private fun SliderRow(
    label: String,
    desc: String?,
    value: Int,
    range: IntRange,
    display: String,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (desc != null) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = display,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // 离散步长：让滑块只能落在整数档位上，
            // 避免出现「4.7 秒」这种没有意义的中间值
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

/** 开关行 */
@Composable
private fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 控件探针页。
 *
 * 这是「方案可维护性」的关键工具：飞书改版后，
 * 用户切到目标界面、回到这里点采集，就能拿到当前界面的
 * 完整控件树，据此编写新的方案 JSON —— **不必等应用发版**。
 *
 * 输出的格式刻意做成可以直接复制粘贴的形式（缩进的树形文本），
 * 因为用户的实际动作就是「复制 → 贴到方案文件里」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeScreen(
    viewModel: ProbeViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val capturing by viewModel.capturing.collectAsStateWithLifecycle()
    val inFeishu by viewModel.inFeishu.collectAsStateWithLifecycle()
    // LocalClipboardManager 已废弃（Compose 新版本会移除），
    // 改用 LocalClipboard。差别是新的 setClipEntry 是挂起函数，
    // 因此需要一个 scope 来调用 —— 复制这种瞬时操作正好适合
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.probe_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.common_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.probe_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = viewModel::capture,
                    enabled = !capturing,
                ) {
                    Text(
                        stringResource(
                            if (capturing) R.string.probe_capturing else R.string.probe_capture,
                        ),
                    )
                }
                if (tree.isNotEmpty()) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            clipboardScope.launch {
                                clipboard.setClipEntry(
                                    android.content.ClipData.newPlainText("probe", tree).let {
                                        androidx.compose.ui.platform.ClipEntry(it)
                                    },
                                )
                            }
                            copied = true
                        },
                    ) {
                        Text(
                            stringResource(
                                if (copied) R.string.probe_copied else R.string.probe_copy,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!inFeishu && tree.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.probe_not_in_feishu),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (tree.isEmpty()) {
                Text(
                    text = stringResource(R.string.probe_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = tree,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
