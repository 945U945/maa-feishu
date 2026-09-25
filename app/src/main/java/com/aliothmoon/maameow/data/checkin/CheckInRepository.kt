package com.aliothmoon.maameow.data.checkin

import android.content.Context
import com.aliothmoon.maameow.domain.checkin.BuiltInProfiles
import com.aliothmoon.maameow.domain.checkin.CheckInAction
import com.aliothmoon.maameow.domain.checkin.CheckInEngine
import com.aliothmoon.maameow.domain.checkin.CheckInProfile
import com.aliothmoon.maameow.domain.checkin.CheckInRule
import com.aliothmoon.maameow.domain.checkin.ImageTemplate
import com.aliothmoon.maameow.domain.checkin.MatchMode
import com.aliothmoon.maameow.domain.checkin.NodeSelector
import com.aliothmoon.maameow.domain.checkin.Roi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 打卡方案仓库。
 *
 * 持久化策略：JSON 文件落盘（而非 DataStore 或数据库）。
 * 原因有三：
 * 1. 方案是需要被用户**导出、回传、分享**的数据结构，JSON 天然适合；
 * 2. 用户回传控件探针结果后，可直接编辑 JSON 微调规则，无需重新编译；
 * 3. 避免为几个配置对象引入数据库依赖。
 *
 * 存储位置：filesDir/checkin_profiles.json
 */
class CheckInRepository(
    private val context: Context,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val writeMutex = Mutex()

    private val _profiles = MutableStateFlow<List<CheckInProfile>>(emptyList())

    /** 当前全部方案，UI 可直接订阅。 */
    val profiles: StateFlow<List<CheckInProfile>> = _profiles.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)

    /** 数据是否已从磁盘加载完成；导入导出等操作需等待它为 true，避免读到空数据。 */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _activeProfileId = MutableStateFlow("")

    /**
     * 当前活跃方案 id。
     * 打卡执行时若未显式指定 profileId，则回退到它。
     */
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /** 设置活跃方案；传入的 id 不存在时忽略。 */
    fun setActiveProfileId(id: String) {
        if (id.isNotEmpty() && getById(id) == null) return
        _activeProfileId.value = id
    }

    /** 首次使用时写入内置方案。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        load()
        // 默认选中第一个启用的方案，保证「立即打卡」开箱可用
        if (_activeProfileId.value.isEmpty()) {
            _activeProfileId.value = _profiles.value.firstOrNull { it.enabled }?.id
                ?: _profiles.value.firstOrNull()?.id
                ?: ""
        }
        _isLoaded.value = true
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = try {
            if (file.exists()) {
                json.decodeFromString<List<ProfileDto>>(file.readText()).map { it.toDomain() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "打卡方案读取失败，回退到内置方案")
            emptyList()
        }

        // 合并内置方案：内置方案始终存在，用户的参数调整保留；
        // 若内置方案不存在于已存配置中（首次启动或版本升级新增），则补进去。
        val merged = mergeBuiltIns(loaded)
        _profiles.value = merged
        persist(merged)
    }

    /**
     * 合并内置方案。
     *
     * 规则：
     * - 已存在的内置方案（按 id 匹配）保留用户的改动，不覆盖；
     * - 新增的内置方案补入；
     * - 用户自建方案原样保留。
     */
    private fun mergeBuiltIns(existing: List<CheckInProfile>): List<CheckInProfile> {
        val byId = existing.associateBy { it.id }.toMutableMap()
        for (builtIn in BuiltInProfiles.all) {
            byId.putIfAbsent(builtIn.id, builtIn.copy(builtIn = true))
        }
        return byId.values.toList()
    }

    private suspend fun persist(list: List<CheckInProfile>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val dto = list.map { ProfileDto.from(it) }
                file.writeText(json.encodeToString(dto))
            } catch (e: Exception) {
                Timber.e(e, "打卡方案保存失败")
            }
        }
    }

    fun getById(id: String): CheckInProfile? = _profiles.value.find { it.id == id }

    /** 新增或更新方案。 */
    suspend fun upsert(profile: CheckInProfile) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list += profile
        _profiles.value = list
        persist(list)
    }

    /** 删除方案。内置方案不可删除。 */
    suspend fun delete(id: String): Boolean {
        val target = getById(id) ?: return false
        if (target.builtIn) return false
        val list = _profiles.value.filterNot { it.id == id }
        _profiles.value = list
        persist(list)
        return true
    }

    /** 导出全部方案为 JSON 文本，供用户备份或回传。 */
    fun exportJson(): String = json.encodeToString(_profiles.value.map { ProfileDto.from(it) })

    /** 从 JSON 文本导入方案（覆盖同 id 项）。 */
    suspend fun importJson(text: String): Int {
        val incoming = json.decodeFromString<List<ProfileDto>>(text).map { it.toDomain() }
        var count = 0
        val list = _profiles.value.toMutableList()
        for (p in incoming) {
            val idx = list.indexOfFirst { it.id == p.id }
            if (idx >= 0) list[idx] = p else list += p
            count++
        }
        _profiles.value = list
        persist(list)
        return count
    }

    companion object {
        private const val FILE_NAME = "checkin_profiles.json"
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    // ─────────────────────────────────────────────────────────────
    // 序列化 DTO 层
    // 刻意与领域模型分离：领域模型用 sealed/enum 表达清晰语义，
    // DTO 层保持扁平字符串，便于人工编辑 JSON 与跨版本兼容。
    // ─────────────────────────────────────────────────────────────

    @Serializable
    private data class ProfileDto(
        val id: String,
        val name: String,
        val targetPackage: String,
        val engine: String = "ACCESSIBILITY",
        val rules: List<RuleDto> = emptyList(),
        val successRules: List<RuleDto> = emptyList(),
        val successKeywords: List<String> = emptyList(),
        val launchDelayMs: Long = 2500L,
        val windowStart: String? = null,
        val windowEnd: String? = null,
        val enabled: Boolean = true,
        val builtIn: Boolean = false,
    ) {
        fun toDomain(): CheckInProfile = CheckInProfile(
            id = id,
            name = name,
            targetPackage = targetPackage,
            engine = runCatching { CheckInEngine.valueOf(engine) }.getOrDefault(CheckInEngine.ACCESSIBILITY),
            rules = rules.map { it.toDomain() },
            successRules = successRules.map { it.toDomain() },
            successKeywords = successKeywords,
            launchDelayMs = launchDelayMs,
            windowStart = windowStart?.let { runCatching { LocalTime.parse(it, TIME_FORMATTER) }.getOrNull() },
            windowEnd = windowEnd?.let { runCatching { LocalTime.parse(it, TIME_FORMATTER) }.getOrNull() },
            enabled = enabled,
            builtIn = builtIn,
        )

        companion object {
            fun from(p: CheckInProfile): ProfileDto = ProfileDto(
                id = p.id,
                name = p.name,
                targetPackage = p.targetPackage,
                engine = p.engine.name,
                rules = p.rules.map { RuleDto.from(it) },
                successRules = p.successRules.map { RuleDto.from(it) },
                successKeywords = p.successKeywords,
                launchDelayMs = p.launchDelayMs,
                windowStart = p.windowStart?.format(TIME_FORMATTER),
                windowEnd = p.windowEnd?.format(TIME_FORMATTER),
                enabled = p.enabled,
                builtIn = p.builtIn,
            )
        }
    }

    @Serializable
    private data class RuleDto(
        val name: String,
        val action: String,
        val selector: SelectorDto? = null,
        val template: TemplateDto? = null,
        val timeoutMs: Long = 5000L,
        val optional: Boolean = false,
    ) {
        fun toDomain(): CheckInRule = CheckInRule(
            name = name,
            action = runCatching { CheckInAction.valueOf(action) }.getOrDefault(CheckInAction.CLICK),
            selector = selector?.toDomain(),
            template = template?.toDomain(),
            timeoutMs = timeoutMs,
            optional = optional,
        )

        companion object {
            fun from(r: CheckInRule): RuleDto = RuleDto(
                name = r.name,
                action = r.action.name,
                selector = r.selector?.let { SelectorDto.from(it) },
                template = r.template?.let { TemplateDto.from(it) },
                timeoutMs = r.timeoutMs,
                optional = r.optional,
            )
        }
    }

    @Serializable
    private data class SelectorDto(
        val text: String? = null,
        val viewId: String? = null,
        val contentDesc: String? = null,
        val matchMode: String = "CONTAINS",
        val clickableOnly: Boolean = true,
        val index: Int = 0,
    ) {
        fun toDomain(): NodeSelector = NodeSelector(
            text = text,
            viewId = viewId,
            contentDesc = contentDesc,
            matchMode = runCatching { MatchMode.valueOf(matchMode) }.getOrDefault(MatchMode.CONTAINS),
            clickableOnly = clickableOnly,
            index = index,
        )

        companion object {
            fun from(s: NodeSelector): SelectorDto = SelectorDto(
                text = s.text,
                viewId = s.viewId,
                contentDesc = s.contentDesc,
                matchMode = s.matchMode.name,
                clickableOnly = s.clickableOnly,
                index = s.index,
            )
        }
    }

    @Serializable
    private data class TemplateDto(
        val assetPath: String,
        val threshold: Double = 0.90,
        val roi: RoiDto? = null,
    ) {
        fun toDomain(): ImageTemplate = ImageTemplate(
            assetPath = assetPath,
            threshold = threshold,
            roi = roi?.toDomain(),
        )

        companion object {
            fun from(t: ImageTemplate): TemplateDto = TemplateDto(
                assetPath = t.assetPath,
                threshold = t.threshold,
                roi = t.roi?.let { RoiDto.from(it) },
            )
        }
    }

    @Serializable
    private data class RoiDto(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun toDomain(): Roi = Roi(left, top, right, bottom)

        companion object {
            fun from(r: Roi): RoiDto = RoiDto(r.left, r.top, r.right, r.bottom)
        }
    }
}
