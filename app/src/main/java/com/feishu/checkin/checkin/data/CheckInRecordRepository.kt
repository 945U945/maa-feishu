package com.feishu.checkin.checkin.data

import com.feishu.checkin.checkin.model.CheckInRecord
import com.feishu.checkin.checkin.model.CheckInResult
import com.feishu.checkin.core.log.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 打卡记录的持久化。
 *
 * ## 存储格式：一次触发一个文件
 *
 * 借鉴 MAA 的 ScheduleTriggerLogger，每次执行写一个独立文件而不是
 * 追加到同一个大文件里。原因：
 *
 * - **列表渲染快**：只需读每个文件的第一行，不必解析整个日志
 * - **失败可定位**：某次执行出问题，直接看那个文件就是完整上下文
 * - **清除粒度细**：可以按时间删旧记录，不会破坏其它记录
 *
 * 文件头部第一行是**摘要行**（用 `#` 开头标记），格式固定为
 * `# 结果 | 类型 | 时间`，列表页用正则解析这一行就够了。
 *
 * ## 为什么不建数据库
 *
 * 一天最多两条记录，一年七百条。Room 带来的编译期成本、
 * schema 迁移负担远超收益。文件方案够用且更透明 ——
 * 用户可以直接把文件导出给开发者排查。
 */
class CheckInRecordRepository(
    private val paths: AppPaths,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 内存中的最近记录缓存，供 UI 直接订阅 */
    private val _recent = MutableStateFlow<List<CheckInRecord>>(emptyList())
    val recent: StateFlow<List<CheckInRecord>> = _recent.asStateFlow()

    /** 启动时或写入后刷新缓存 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _recent.value = loadRecent()
    }

    /**
     * 保存一条记录。
     *
     * 用幂等键作为文件名 —— 这样「同一天同一类型的第二次写入」
     * 会覆盖第一次，而不是产生两条记录。
     * 这正好配合了幂等设计：即便并发触发了两次，落盘的也只有一条。
     */
    suspend fun save(record: CheckInRecord) = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(paths.recordDir, "${record.id}.json")
            file.writeText(json.encodeToString(CheckInRecord.serializer(), record))
            Timber.d("记录已保存: %s", file.name)
            _recent.value = loadRecent()
        }.onFailure { Timber.e(it, "保存打卡记录失败: %s", record.id) }
    }

    /** 查询某天某类型是否已有成功记录（幂等判定用） */
    suspend fun findExisting(dedupeKey: String): CheckInRecord? = withContext(Dispatchers.IO) {
        val file = File(paths.recordDir, "$dedupeKey.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(CheckInRecord.serializer(), file.readText())
        }.onFailure { Timber.w(it, "读取记录失败: %s", dedupeKey) }
            .getOrNull()
    }

    /**
     * 载入最近的记录列表。
     *
     * 上限 200 条：再多用户也不会往下翻，而读 200 个小文件只要几十毫秒。
     * 文件名就是幂等键（`planId_KIND_yyyymmdd`），字典序倒序即时间倒序，
     * 因此**不需要读文件内容就能完成排序** —— 这是把日期编进文件名的额外收益。
     */
    private fun loadRecent(limit: Int = 200): List<CheckInRecord> {
        val files = paths.recordDir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.take(limit)
            ?: return emptyList()

        return files.mapNotNull { f ->
            runCatching {
                json.decodeFromString(CheckInRecord.serializer(), f.readText())
            }.onFailure { Timber.w(it, "跳过损坏的记录文件: %s", f.name) }
                .getOrNull()
        }
    }

    /** 删除一条记录 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            File(paths.recordDir, "$id.json").takeIf { it.exists() }?.delete()
            _recent.value = loadRecent()
        }.onFailure { Timber.w(it, "删除记录失败: %s", id) }
    }

    /** 清空全部记录 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching {
            paths.recordDir.listFiles()?.forEach { it.delete() }
            _recent.value = emptyList()
        }.onFailure { Timber.w(it, "清空记录失败") }
    }

    /**
     * 读取一条记录的原始文件内容（详情页展示时间线用）。
     *
     * 直接返回格式化文本而不是对象，是为了让详情页能把
     * 「用户看不懂的字段」也显示出来 —— 排查问题时原始内容更有价值。
     */
    suspend fun readRaw(id: String): String? = withContext(Dispatchers.IO) {
        File(paths.recordDir, "$id.json")
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
    }

    /** 列表页用的摘要行（不带 `#` 前缀，仅展示） */
    fun summaryLine(record: CheckInRecord): String {
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(record.startedAt).atZone(zone).format(fmt)
        val result = record.result?.name ?: "RUNNING"
        return "$result | ${record.kind.name} | $time"
    }

    /**
     * 统计最近的成功率。
     *
     * 只统计「真正执行过的」（排除 SKIPPED_BUSY），
     * 否则「同一天被手动点击一次」就会把成功率拉低，用户会觉得莫名其妙。
     */
    fun successRate(records: List<CheckInRecord>): Float {
        val attempted = records.filter {
            it.result != null && it.result != CheckInResult.SKIPPED_BUSY
        }
        if (attempted.isEmpty()) return 0f
        val ok = attempted.count { it.result?.isSuccessLike == true }
        return ok.toFloat() / attempted.size
    }

    /** 保留最近 N 天记录，其余删除（默认 180 天） */
    suspend fun prune(keepDays: Int = 180) = withContext(Dispatchers.IO) {
        runCatching {
            val cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L
            val cutoffStamp = Instant.ofEpochMilli(cutoff)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"))

            paths.recordDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                // 文件名形如 planId_KIND_20260925.json，取末尾的日期段比较
                val stamp = f.nameWithoutExtension.substringAfterLast('_')
                if (stamp.length == 8 && stamp < cutoffStamp) {
                    f.delete()
                }
            }
        }.onFailure { Timber.w(it, "清理旧记录失败") }
    }
}
