package com.feishu.checkin.checkin.data

import com.feishu.checkin.checkin.model.CheckInPlan
import com.feishu.checkin.core.log.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * 打卡方案的读取。
 *
 * ## 内置方案 + 自定义方案的合并策略
 *
 * - 内置方案硬编码在 [BuiltInPlans] 里，**不落盘** ——
 *   这样升级版本时内置方案能自动获得修正，不必处理「用户手里是旧方案」的问题
 * - 自定义方案放在 `<filesDir>/FeishuCheckIn/plans/` 下的 json 文件里，
 *   用户可以手工放文件进去（配合「控件探针」导出的结构写 selector）
 * - 同名 ID 时自定义方案覆盖内置 —— 给高级用户一个「改内置方案」的口子，
 *   而不必等版本更新
 *
 * 自定义方案读失败时**只跳过那一份**，不影响其它方案 ——
 * 一份格式错误的 JSON 不应该让用户打不了卡。
 */
class CheckInPlanRepository(
    private val paths: AppPaths,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val plansDir: File
        get() = File(paths.root, "plans").apply { if (!exists()) mkdirs() }

    private val _plans = MutableStateFlow<List<CheckInPlan>>(BuiltInPlans.ALL)
    val plans: StateFlow<List<CheckInPlan>> = _plans.asStateFlow()

    /** 重新扫描方案目录（放入了新 JSON 后调用） */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _plans.value = loadAll()
    }

    /**
     * 加载全部方案。
     *
     * 合并顺序：先内置，再自定义覆盖。用 LinkedHashMap 保证顺序稳定 ——
     * 顺序变了会让设置页列表跳动，用户以为点错了。
     */
    private fun loadAll(): List<CheckInPlan> {
        val merged = LinkedHashMap<String, CheckInPlan>()
        BuiltInPlans.ALL.forEach { merged[it.id] = it }
        loadCustom().forEach { merged[it.id] = it }

        // 校验：步骤为空或选择器全部无效的方案直接剔除，
        // 否则执行时会在第一步就失败，报错信息还不明确
        return merged.values.filter { plan ->
            val usable = plan.steps.isNotEmpty() && plan.steps.any { it.target.isValid }
            if (!usable) Timber.w("方案 %s 无有效步骤，已忽略", plan.id)
            usable
        }
    }

    private fun loadCustom(): List<CheckInPlan> {
        val files = plansDir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                json.decodeFromString(CheckInPlan.serializer(), f.readText())
            }.onFailure { Timber.w(it, "跳过无法解析的方案文件: %s", f.name) }
                .getOrNull()
        }
    }

    /** 按 ID 取方案，找不到时回退到第一个内置方案 */
    fun byId(id: String): CheckInPlan =
        _plans.value.firstOrNull { it.id == id }
            ?: BuiltInPlans.ALL.first()

    /** 当前方案的同步快照 */
    fun snapshot(): List<CheckInPlan> = _plans.value
}
