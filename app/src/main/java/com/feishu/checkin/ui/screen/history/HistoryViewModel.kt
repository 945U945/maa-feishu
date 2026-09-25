package com.feishu.checkin.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feishu.checkin.checkin.data.CheckInRecordRepository
import com.feishu.checkin.checkin.model.CheckInRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 记录页状态。
 */
data class HistoryUiState(
    val records: List<CheckInRecord> = emptyList(),
    /** 选中查看详情的记录，null 表示未展开 */
    val selected: CheckInRecord? = null,
    /** 选中记录的原始 JSON 文本 */
    val selectedRaw: String = "",
    val loading: Boolean = true,
)

/**
 * 记录页 ViewModel。
 *
 * 只读不写（除了删除单条）—— 记录由执行引擎写入，
 * 这里负责展示。因此逻辑很少，主要工作是：
 * 1. 按成功/失败分组（UI 需要区分展示）
 * 2. 提供筛选
 */
class HistoryViewModel(
    private val recordRepository: CheckInRecordRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(RecordFilter.ALL)
    val filter: StateFlow<RecordFilter> = _filter.asStateFlow()

    private val _selected = MutableStateFlow<CheckInRecord?>(null)
    private val _selectedRaw = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> = kotlinx.coroutines.flow.combine(
        recordRepository.recent,
        _filter,
        _selected,
        _selectedRaw,
    ) { records, filter, selected, raw ->
        HistoryUiState(
            records = records.filter { filter.matches(it) },
            selected = selected,
            selectedRaw = raw,
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(),
    )

    /** 统计信息 */
    val stats: StateFlow<HistoryStats> = recordRepository.recent
        .map { records ->
            HistoryStats(
                total = records.count { it.result != null },
                success = records.count { it.result?.isSuccessLike == true },
                failure = records.count { it.result?.isFailure == true },
                successRate = recordRepository.successRate(records),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = HistoryStats(),
        )

    fun setFilter(filter: RecordFilter) {
        _filter.value = filter
    }

    /** 展开某条记录的详情，并读取原始内容 */
    fun select(record: CheckInRecord?) {
        _selected.value = record
        if (record == null) {
            _selectedRaw.value = ""
            return
        }
        viewModelScope.launch {
            runCatching {
                _selectedRaw.value = recordRepository.readRaw(record.id).orEmpty()
            }.onFailure { Timber.w(it, "读取记录原文失败") }
        }
    }

    fun delete(record: CheckInRecord) {
        viewModelScope.launch {
            runCatching { recordRepository.delete(record.id) }
                .onFailure { Timber.w(it, "删除记录失败") }
            if (_selected.value?.id == record.id) select(null)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { recordRepository.refresh() }
                .onFailure { Timber.w(it, "刷新记录失败") }
        }
    }
}

/** 记录筛选条件 */
enum class RecordFilter {
    ALL,

    /** 只看成功（含已打卡） */
    SUCCESS,

    /** 只看失败 —— 这是用户最需要关注的 */
    FAILURE;

    fun matches(record: CheckInRecord): Boolean {
        val result = record.result ?: return this == ALL
        return when (this) {
            ALL -> true
            SUCCESS -> result.isSuccessLike
            FAILURE -> result.isFailure
        }
    }
}

/** 统计信息 */
data class HistoryStats(
    val total: Int = 0,
    val success: Int = 0,
    val failure: Int = 0,
    val successRate: Float = 0f,
)
