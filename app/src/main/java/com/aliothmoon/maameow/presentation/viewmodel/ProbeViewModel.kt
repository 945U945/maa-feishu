package com.aliothmoon.maameow.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maameow.domain.checkin.CollectNodeTreeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 控件探针状态。
 *
 * @param output      采集到的控件树文本
 * @param capturing   是否正在采集
 * @param error       错误提示（无障碍未开、界面为空等）
 * @param copied      是否已复制（用于短暂提示）
 * @param nodeCount   采到的节点数量
 */
data class ProbeUiState(
    val output: String = "",
    val capturing: Boolean = false,
    val error: String = "",
    val copied: Boolean = false,
    val nodeCount: Int = 0,
)

/**
 * 控件探针 ViewModel。
 *
 * 采集动作涉及跨进程调用无障碍服务 + 字符串构建，
 * 放到 ViewModel 里统一管理生命周期，避免 Composable 重组时重复触发。
 */
class ProbeViewModel(
    private val collectNodeTree: CollectNodeTreeUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = _state.asStateFlow()

    /** 采集当前界面控件树。 */
    fun capture() {
        if (_state.value.capturing) return
        _state.update { it.copy(capturing = true, error = "", copied = false) }

        viewModelScope.launch {
            try {
                when (val result = collectNodeTree()) {
                    is CollectNodeTreeUseCase.Result.Success -> {
                        _state.update {
                            it.copy(
                                output = result.text,
                                nodeCount = result.nodeCount,
                                capturing = false,
                                error = "",
                            )
                        }
                    }

                    CollectNodeTreeUseCase.Result.AccessibilityNotReady -> {
                        _state.update {
                            it.copy(
                                capturing = false,
                                error = "无障碍服务未开启。请到「系统设置 → 无障碍」中找到本应用并开启",
                            )
                        }
                    }

                    CollectNodeTreeUseCase.Result.EmptyTree -> {
                        _state.update {
                            it.copy(
                                capturing = false,
                                error = "未读取到控件信息。请确认已切换到目标应用界面后重试",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(capturing = false, error = "采集失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 标记已复制。 */
    fun markCopied() {
        _state.update { it.copy(copied = true) }
    }

    /** 清空结果。 */
    fun clear() {
        _state.update { ProbeUiState() }
    }
}
