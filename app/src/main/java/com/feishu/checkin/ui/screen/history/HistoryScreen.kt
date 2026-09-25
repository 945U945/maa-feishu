package com.feishu.checkin.ui.screen.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.feishu.checkin.R
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInPhase
import com.feishu.checkin.checkin.model.CheckInRecord
import com.feishu.checkin.checkin.model.CheckInResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记录页。
 *
 * ## 核心设计：让失败显眼
 *
 * 这是「结果语义分离」在设计上的落点。列表里：
 *
 * - 成功与已打卡 → 绿色对勾
 * - 失败 → 红色感叹号 + 具体原因
 * - 跳过（SKIPPED_BUSY）→ 灰色信息图标，**不标红**
 *
 * 如果「跳过」也标红，用户会习惯性忽略所有红色，
 * 于是真正的失败被淹没。这是这一页最重要的设计决策。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var detailRecord by remember { mutableStateOf<CheckInRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.history_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 统计条
            if (stats.total > 0) {
                StatsRow(stats)
            }

            // 筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == RecordFilter.ALL,
                    onClick = { viewModel.setFilter(RecordFilter.ALL) },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = filter == RecordFilter.SUCCESS,
                    onClick = { viewModel.setFilter(RecordFilter.SUCCESS) },
                    label = { Text(stringResource(R.string.result_success)) },
                )
                FilterChip(
                    selected = filter == RecordFilter.FAILURE,
                    onClick = { viewModel.setFilter(RecordFilter.FAILURE) },
                    label = { Text(stringResource(R.string.result_failed_unknown)) },
                )
            }

            if (state.records.isEmpty() && !state.loading) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.records, key = { it.id }) { record ->
                        RecordCard(
                            record = record,
                            onClick = { detailRecord = record },
                        )
                    }
                }
            }
        }
    }

    // 详情底部弹窗
    detailRecord?.let { record ->
        RecordDetailSheet(
            record = record,
            raw = state.selectedRaw,
            onDismiss = {
                detailRecord = null
                viewModel.select(null)
            },
        )
    }

    // 选中时加载原始内容
    androidx.compose.runtime.LaunchedEffect(detailRecord) {
        viewModel.select(detailRecord)
    }
}

/** 统计条 */
@Composable
private fun StatsRow(stats: HistoryStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem(
                value = "${(stats.successRate * 100).toInt()}%",
                label = "成功率",
                color = MaterialTheme.colorScheme.primary,
            )
            StatItem(value = "${stats.success}", label = "正常", color = SuccessGreen)
            StatItem(
                value = "${stats.failure}",
                label = "失败",
                color = if (stats.failure > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun StatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空状态 */
@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单条记录卡片 */
@Composable
private fun RecordCard(record: CheckInRecord, onClick: () -> Unit) {
    val result = record.result
    val (icon, tint) = when {
        result == null -> Icons.Filled.Info to MaterialTheme.colorScheme.outline

        // 正常终态（含已打卡）—— 绿色对勾
        result.isSuccessLike -> Icons.Filled.CheckCircle to SuccessGreen

        // 跳过 —— 灰色，刻意不标红
        result == CheckInResult.SKIPPED_BUSY ->
            Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant

        // 真失败 —— 红色
        else -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resultLabel(result),
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(kindLabel(record.kind))
                        append(" · ")
                        append(formatTime(record.startedAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (record.durationMs > 0) {
                Text(
                    text = "%.1fs".format(record.durationMs / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 详情底部弹窗。
 *
 * 展示执行时间线 —— 这是排查「卡在哪一步」的关键信息。
 * 用户能直接看到「等待考勤页就绪」这一步花了 15 秒然后超时，
 * 而不是只有一个「失败」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDetailSheet(
    record: CheckInRecord,
    raw: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.history_detail_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))

            // 概览
            DetailRow("结果", resultLabel(record.result))
            DetailRow("类型", kindLabel(record.kind))
            DetailRow("开始时间", formatFullTime(record.startedAt))
            if (record.finishedAt > 0) {
                DetailRow("结束时间", formatFullTime(record.finishedAt))
            }
            if (record.durationMs > 0) {
                DetailRow("总耗时", "%.1f 秒".format(record.durationMs / 1000f))
            }
            if (record.message.isNotEmpty()) {
                DetailRow("说明", record.message)
            }

            // 时间线
            if (record.timeline.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "执行时间线",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                val base = record.timeline.first().at
                record.timeline.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Text(
                            text = "+%dms".format(entry.at - base),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            text = phaseLabel(entry.phase),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(80.dp),
                        )
                        if (entry.note.isNotEmpty()) {
                            Text(
                                text = entry.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 原始内容（折叠展示，排障用）
            if (raw.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "原始记录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ────────────────────── 辅助 ──────────────────────

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

private fun phaseLabel(phase: CheckInPhase): String = when (phase) {
    CheckInPhase.PREPARING -> "准备"
    CheckInPhase.LAUNCHING -> "启动飞书"
    CheckInPhase.NAVIGATING -> "导航"
    CheckInPhase.CHECKING_IN -> "点击打卡"
    CheckInPhase.VERIFYING -> "确认结果"
    CheckInPhase.DONE -> "完成"
}

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFmt)

private fun formatFullTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFmt)
