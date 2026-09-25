package com.aliothmoon.maameow.data.preferences

import com.aliothmoon.maameow.data.notification.NotificationSettings
import com.aliothmoon.maameow.domain.models.AppSettings
import com.aliothmoon.maameow.schedule.model.ScheduleStrategy
import kotlinx.serialization.Serializable

/**
 * 配置备份包。
 *
 * 打卡版把原工程里的游戏任务链（TaskProfile）替换为打卡方案的原始 JSON：
 * 打卡方案内部结构复杂（规则/选择器/模板/ROI 多层嵌套），直接复用
 * [com.aliothmoon.maameow.data.checkin.CheckInRepository] 的序列化层，
 * 这里只保留一个不透明的 JSON 字符串，避免两套 DTO 漂移。
 */
@Serializable
data class ConfigBackup(
    val version: Int = 1,
    val exportedAt: String = "",
    val appSettings: AppSettings,
    val notificationSettings: NotificationSettings,
    /** 打卡方案列表的 JSON（由 CheckInRepository.exportJson 产出） */
    val checkInProfilesJson: String = "[]",
    val scheduleStrategies: List<ScheduleStrategy>
)
