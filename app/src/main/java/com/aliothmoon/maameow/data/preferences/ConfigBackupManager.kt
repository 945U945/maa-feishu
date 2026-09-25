package com.aliothmoon.maameow.data.preferences

import com.aliothmoon.maameow.constant.OFFICIAL_SHIZUKU_PACKAGE
import com.aliothmoon.maameow.data.checkin.CheckInRepository
import com.aliothmoon.maameow.data.notification.NotificationSettings
import com.aliothmoon.maameow.data.notification.NotificationSettingsManager
import com.aliothmoon.maameow.data.notification.reapplyWebhookPresetIfBlank
import com.aliothmoon.maameow.domain.models.AppSettings
import com.aliothmoon.maameow.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maameow.schedule.service.ScheduleAlarmManager
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 配置导入导出。
 *
 * 打卡版覆盖的范围：应用设置、外部通知设置、打卡方案、定时策略。
 */
class ConfigBackupManager(
    private val appSettingsManager: AppSettingsManager,
    private val notificationSettingsManager: NotificationSettingsManager,
    private val checkInRepository: CheckInRepository,
    private val scheduleStrategyRepository: ScheduleStrategyRepository,
    private val scheduleAlarmManager: ScheduleAlarmManager,
) {
    private val json = Json(JsonUtils.common) {
        prettyPrint = true
    }

    suspend fun exportTo(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        // 等待异步数据加载完成，避免导出空数据
        scheduleStrategyRepository.isLoaded.first { it }

        val backup = ConfigBackup(
            version = CURRENT_VERSION,
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            appSettings = appSettingsManager.settings.first().sanitized(),
            notificationSettings = notificationSettingsManager.settings.first()
                .sanitizedForExport(),
            checkInProfilesJson = checkInRepository.exportJson(),
            scheduleStrategies = scheduleStrategyRepository.strategies.value,
        )
        outputStream.bufferedWriter().use { writer ->
            writer.write(json.encodeToString(ConfigBackup.serializer(), backup))
        }
    }

    /**
     * 导入配置。
     * 注意：AppSettings 采用整包写入，部分设置（如 startupBackend、debugMode）的运行态副作用
     * 不会立刻触发，建议导入后重启应用以确保所有设置完全生效。
     */
    suspend fun importFrom(inputStream: InputStream) = withContext(Dispatchers.IO) {
        val content = inputStream.bufferedReader().use { it.readText() }
        val backup = json.decodeFromString(ConfigBackup.serializer(), content)
        require(backup.version <= CURRENT_VERSION) {
            "不支持的备份版本: ${backup.version}，当前最高支持: $CURRENT_VERSION"
        }
        // 自定义背景的开关与令牌指向本机文件，导入其他设备的配置时保留本机值。
        val localSettings = appSettingsManager.settings.first()
        appSettingsManager.setSettings(
            backup.appSettings.normalizedForImport().copy(
                customBackgroundEnabled = localSettings.customBackgroundEnabled,
                customBackgroundToken = localSettings.customBackgroundToken,
            )
        )
        notificationSettingsManager.updateSettings(backup.notificationSettings.reapplyWebhookPresetIfBlank())

        // 打卡方案：由仓库自带的反序列化层接管
        if (backup.checkInProfilesJson.isNotBlank()) {
            checkInRepository.importJson(backup.checkInProfilesJson)
        }

        // 先取消旧闹钟，再导入并重新注册
        val oldStrategies = scheduleStrategyRepository.strategies.value
        oldStrategies.forEach { scheduleAlarmManager.cancel(it.id) }
        scheduleStrategyRepository.importStrategies(backup.scheduleStrategies)
        scheduleAlarmManager.rescheduleAll(backup.scheduleStrategies)
    }

    companion object {
        const val CURRENT_VERSION = 1

        /**
         * 导出时剥离设备本地字段：CDK、解锁 PIN 属敏感信息；
         * 自定义背景的开关与令牌对应本机 filesDir 下的图片文件，在其他设备上不存在。
         */
        private fun AppSettings.sanitized() = copy(
            mirrorChyanCdk = "",
            wakeCredential = "",
            customBackgroundEnabled = "false",
            customBackgroundToken = "",
        )

        /**
         * 导入时对已废弃或非法的旧值做归一化，避免后续读取时违反非空约束。
         */
        private fun AppSettings.normalizedForImport() = copy(
            shizukuLaunchPackage = shizukuLaunchPackage.ifBlank { OFFICIAL_SHIZUKU_PACKAGE }
        )
    }
}

// 导出脱敏：凭证清空，识别类字段（userId/chatId 等）保留
internal fun NotificationSettings.sanitizedForExport() = copy(
    serverChanSendKey = "",
    discordBotToken = "",
    discordWebhookUrl = "",
    smtpPassword = "",
    barkSendKey = "",
    telegramBotToken = "",
    dingTalkAccessToken = "",
    dingTalkSecret = "",
    kookBotToken = "",
    qmsgKey = "",
    gotifyToken = "",
    customWebhookUrl = "",
    customWebhookHeaders = "",
)
