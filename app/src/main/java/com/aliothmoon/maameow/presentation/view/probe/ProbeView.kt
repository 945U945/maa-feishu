package com.aliothmoon.maameow.presentation.view.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maameow.presentation.viewmodel.ProbeViewModel

/**
 * 控件探针界面。
 *
 * ## 这个界面为什么重要
 *
 * 飞书对打卡页启用了防截屏，用户截不出图，也就没法用传统方式
 * 说明"打卡按钮长什么样、在哪"。而打卡规则必须知道这些信息才能配置。
 *
 * 解决方案：让应用自己读**控件树**（结构化的文字/ID/坐标，
 * 不走截屏通道，因此不受防截屏限制）并导出成文本。
 * 用户三步即可完成：
 *   1. 打开飞书，进入考勤打卡页
 *   2. 切回本应用，点「采集当前界面」
 *   3. 复制结果，用于配置打卡规则
 *
 * ## 交互设计考虑
 *
 * - 结果用等宽字体：控件树带缩进层级，等宽才能看清结构
 * - 结果区限高可滚动：控件树可能几百行，不能把按钮挤出屏幕
 * - 提供「复制」而非只有分享：多数用户会直接粘贴到聊天窗口
 */
@Composable
fun ProbeView(
    viewModel: ProbeViewModel,
    onOpenTargetApp: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 说明卡片 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "为什么需要这个功能",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "飞书对打卡页开启了防截屏保护，系统截图会得到黑屏，" +
                            "因此无法用图像识别定位打卡按钮。\n\n" +
                            "本功能改用「读控件树」获取界面信息——它不是截图，" +
                            "而是直接读取界面上的文字、控件 ID 和坐标，不受防截屏限制。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ── 操作步骤 ──
        Text("操作步骤", style = MaterialTheme.typography.titleSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StepText(1, "打开飞书，进入考勤打卡页面")
                StepText(2, "切回本应用（不要关掉飞书），点「采集当前界面」")
                StepText(3, "复制采集结果，即可用于配置打卡规则")
            }
        }

        // ── 操作按钮 ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.capture() },
                enabled = !state.capturing,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.capturing) "采集中…" else "采集当前界面")
            }
            OutlinedButton(
                onClick = onOpenTargetApp,
                modifier = Modifier.weight(1f),
            ) {
                Text("打开飞书")
            }
        }

        // ── 提示信息 ──
        if (state.error.isNotBlank()) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state.copied) {
            Text(
                text = "已复制到剪贴板",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (state.nodeCount > 0) {
            Text(
                text = "已采集 ${state.nodeCount} 个控件节点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ── 结果区 ──
        if (state.output.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "采集结果",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.output))
                    viewModel.markCopied()
                }) {
                    Text("复制")
                }
                TextButton(onClick = { viewModel.clear() }) {
                    Text("清空")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.output,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun StepText(index: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
