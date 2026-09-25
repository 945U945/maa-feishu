package com.feishu.checkin.ui.screen.home

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.feishu.checkin.R
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInRecord
import com.feishu.checkin.checkin.model.CheckInResult
import com.feishu.checkin.core.device.HealthItem
import com.feishu.checkin.core.device.HealthStatus
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 首页。
 *
 * ## 信息层次
 *
 * 页面按「用户最关心什么」自上而下排列：
 *
 * 1. **总开关** —— 最重要，决定一切
 * 2. **下次执行时间** —— 用户最常来看的：「明天几点会打」
 * 3. **上次结果** —— 确认「今天打卡成功了吗」
 * 4. **立即打卡** —— 手动兜底
 * 5. **运行环境检查** —— 只在有问题时才显眼
 *
 * 前四项是日常必看，第五项是「出问题了才看」，
 * 因此放在最下面且默认紧凑展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingKind by remember { mutableStateOf<CheckInKind?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.home_title)) })
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
            MasterSwitchCard(
                enabled = state.settings.enabled,
                running = state.running,
                onToggle = viewModel::setEnabled,
            )

            if (state.settings.enabled) {
                NextTriggerCard(
                    nextTriggerAt = state.nextTriggerAt,
                    settings = state.settings,
                    onEditClockIn = { editingKind = CheckInKind.CLOCK_IN },
                    onEditClockOut = { editingKind = CheckInKind.CLOCK_OUT },
                    onToggleClockIn = { viewModel.setKindEnabled(CheckInKind.CLOCK_IN, it) },
                    onToggleClockOut = { viewModel.setKindEnabled(CheckInKind.CLOCK_OUT, it) },
                    onToggleWeekdays = viewModel::setWeekdaysOnly,
                )
            }

            LastResultCard(record = state.lastRecord)

            RunNowCard(
                running = state.running,
                enabled = state.settings.enabled,
                onRunClickIn = { viewModel.runNow(CheckInKind.CLOCK_IN) },
                onRunClickOut = { viewModel.runNow(CheckInKind.CLOCK_OUT) },
            )

            HealthCard(
                items = state.health,
                onFix = viewModel::openSettings,
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    // 时间选择对话框
    editingKind?.let { kind ->
        TimePickerDialog(
            initial = state.settings.timeOf(kind),
            onDismiss = { editingKind = null },
            onConfirm = { time ->
                viewModel.setTime(kind, time)
                editingKind = null
            },
        )
    }
}

/** 总开关卡片 */
@Composable
private fun MasterSwitchCard(
    enabled: Boolean,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_enabled),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** 下次执行时间 + 时间配置 */
@Composable
private fun NextTriggerCard(
    nextTriggerAt: Long?,
    settings: com.feishu.checkin.core.time.AppSettings,
    onEditClockIn: () -> Unit,
    onEditClockOut: () -> Unit,
    onToggleClockIn: (Boolean) -> Unit,
    onToggleClockOut: (Boolean) -> Unit,
    onToggleWeekdays: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 下次执行时间 —— 页面最醒目的信息
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_next_trigger),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = nextTriggerAt?.let { formatFull(it) }
                    ?: stringResource(R.string.home_no_next),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // 上班打卡
            TimeRow(
                label = stringResource(R.string.home_morning_time),
                time = settings.clockInTime,
                enabled = settings.clockInEnabled,
                onToggle = onToggleClockIn,
                onEdit = onEditClockIn,
            )

            // 下班打卡
            TimeRow(
                label = stringResource(R.string.home_evening_time),
                time = settings.clockOutTime,
                enabled = settings.clockOutEnabled,
                onToggle = onToggleClockOut,
                onEdit = onEditClockOut,
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_weekdays_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_weekdays_only_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.weekdaysOnly,
                    onCheckedChange = onToggleWeekdays,
                )
            }
        }
    }
}

/** 一行时间配置 */
@Composable
private fun TimeRow(
    label: String,
    time: LocalTime,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.size(width = 52.dp, height = 32.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit, enabled = enabled) {
            Text(
                text = time.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 上次结果 */
@Composable
private fun LastResultCard(record: CheckInRecord?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_last_result),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (record == null) {
                Text(
                    text = stringResource(R.string.home_last_result_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val result = record.result
            // 颜色语义：成功绿、失败红。注意这里不区分「已打卡」与「成功」——
            // 两者对用户都是好消息，都用绿色；而 SKIPPED_BUSY 用中性色，
            // 因为它既不是成功也不是失败
            val tint = when {
                result == null -> MaterialTheme.colorScheme.onSurfaceVariant
                result.isSuccessLike -> SuccessGreen
                result == CheckInResult.SKIPPED_BUSY -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (result?.isSuccessLike == true) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Error
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resultLabel(result),
                        style = MaterialTheme.typography.titleMedium,
                        color = tint,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = buildString {
                            append(kindLabel(record.kind))
                            append(" · ")
                            append(formatShort(record.startedAt))
                            if (record.durationMs > 0) {
                                append(" · 耗时 ")
                                append("%.1f 秒".format(record.durationMs / 1000f))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (record.message.isNotEmpty() && result?.isSuccessLike != true) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = record.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 立即打卡 */
@Composable
private fun RunNowCard(
    running: Boolean,
    enabled: Boolean,
    onRunClickIn: () -> Unit,
    onRunClickOut: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.home_run_now),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_run_now_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (running) {
                // 执行中显示进度而不是禁用按钮 —— 用户能看到「在动」，
                // 比一个灰掉的按钮更让人安心
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.result_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRunClickIn,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.home_clock_in_action))
                    }
                    OutlinedButton(
                        onClick = onRunClickOut,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.home_clock_out_action))
                    }
                }
            }
        }
    }
}

/**
 * 运行环境检查。
 *
 * 只在有问题时使用警示色，全绿时用一句简洁的确认 ——
 * 避免制造无谓的焦虑。这一项的设计目标是「有问题时显眼、
 * 没问题时不打扰」。
 */
@Composable
private fun HealthCard(
    items: List<HealthStatus>,
    onFix: (String) -> Unit,
) {
    // 「需手动确认」的自启动项不算问题，只是提示
    val issues = items.filter { !it.ok && !it.manualOnly }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (issues.isEmpty()) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.health_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            if (issues.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.health_all_ok),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SuccessGreen,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.health_has_issues, issues.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                issues.forEach { status ->
                    HealthRow(status = status, onFix = onFix)
                }
            }
        }
    }
}

@Composable
private fun HealthRow(status: HealthStatus, onFix: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = healthItemLabel(status.item),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (status.ok) {
                    stringResource(R.string.health_ok)
                } else {
                    stringResource(R.string.health_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status.ok) SuccessGreen else MaterialTheme.colorScheme.error,
            )
        }
        status.settingsAction?.let { action ->
            TextButton(onClick = { onFix(action) }) {
                Text(stringResource(R.string.health_fix))
            }
        }
    }
}

/** 时间选择对话框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(LocalTime.of(pickerState.hour, pickerState.minute))
            }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = pickerState)
            }
        },
    )
}

// ────────────────────── 辅助 ──────────────────────

/** 成功/就绪用的绿色。用固定值而非主题色，保证语义一致 */
private val SuccessGreen = Color(0xFF1B7F4B)

@Composable
private fun resultLabel(result: CheckInResult?): String = when (result) {
    CheckInResult.SUCCESS -> stringResource(R.string.result_success)
    CheckInResult.ALREADY_DONE -> stringResource(R.string.result_already_done)
    CheckInResult.ENTRY_NOT_FOUND -> stringResource(R.string.result_failed_not_found)
    CheckInResult.NETWORK_ERROR -> stringResource(R.string.result_failed_network)
    CheckInResult.DEVICE_LOCKED -> stringResource(R.string.result_failed_locked)
    CheckInResult.PERMISSION_MISSING -> stringResource(R.string.result_failed_permission)
    CheckInResult.SKIPPED_BUSY -> stringResource(R.string.result_skipped_busy)
    CheckInResult.FAILURE -> stringResource(R.string.result_failed_unknown)
    null -> stringResource(R.string.result_running)
}

@Composable
private fun kindLabel(kind: CheckInKind): String = when (kind) {
    CheckInKind.CLOCK_IN -> stringResource(R.string.home_kind_clock_in)
    CheckInKind.CLOCK_OUT -> stringResource(R.string.home_kind_clock_out)
}

@Composable
private fun healthItemLabel(item: HealthItem): String = when (item) {
    HealthItem.ACCESSIBILITY -> stringResource(R.string.health_accessibility)
    HealthItem.NOTIFICATION -> stringResource(R.string.health_notification)
    HealthItem.EXACT_ALARM -> stringResource(R.string.health_exact_alarm)
    HealthItem.BATTERY_OPTIMIZATION -> stringResource(R.string.health_battery)
    HealthItem.AUTO_START -> stringResource(R.string.health_autostart)
}

private val fullFmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val shortFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatFull(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(fullFmt)

private fun formatShort(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(shortFmt)
