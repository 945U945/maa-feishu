package com.aliothmoon.maameow.presentation.view.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maameow.BuildConfig
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.constant.DefaultDisplayConfig
import com.aliothmoon.maameow.data.model.update.UpdateChannel
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.domain.models.RemoteBackend
import com.aliothmoon.maameow.domain.models.UnlockCredential
import com.aliothmoon.maameow.domain.models.UnlockGesture
import com.aliothmoon.maameow.domain.models.UnlockStep
import com.aliothmoon.maameow.presentation.components.LogExportController
import com.aliothmoon.maameow.presentation.components.SectionHeader
import com.aliothmoon.maameow.presentation.components.SettingRow
import com.aliothmoon.maameow.presentation.components.TopAppBar
import com.aliothmoon.maameow.presentation.viewmodel.SettingsViewModel
import com.aliothmoon.maameow.theme.MaaDesignTokens
import com.aliothmoon.maameow.utils.i18n.asString
import com.aliothmoon.maameow.utils.i18n.resolve

/**
 * 设置页（飞书打卡版）。
 *
 * 相比原 MAA-Meow 工程，彻底移除了：
 * - 核心数据目录 / 资源初始化 / 干员识别（MaaCore 相关）
 * - 企鹅物流上报、一图流 Token、任务覆盖编辑器（游戏数据上报）
 * - 成就系统、首启引导、公告弹窗、画中画
 *
 * 只保留「定时唤醒 + 打卡」真正需要的四块：
 * 1. 打卡目标（当前目标应用、控件探针入口）
 * 2. 定时唤醒解锁（解锁方式 / 凭证 / 手势录制 / 自测）
 * 3. 外观与通用（主题、语言、字号、自定义背景、更新、调试、导入导出）
 * 4. 运行日志与关于
 */
@Composable
fun SettingsView(
    viewModel: SettingsViewModel,
    onNavigateToNotification: () -> Unit = {},
    onNavigateToLogHistory: () -> Unit = {},
    onNavigateToErrorLog: () -> Unit = {},
    onNavigateToWallpaper: () -> Unit = {},
) {
    val context = LocalContext.current

    val settingsMessage by viewModel.settingsMessage.collectAsStateWithLifecycle()
    val showRestartDialog by viewModel.showRestartDialog.collectAsStateWithLifecycle()

    val startupBackend by viewModel.startupBackend.collectAsStateWithLifecycle()
    val skipShizukuCheck by viewModel.skipShizukuCheck.collectAsStateWithLifecycle()

    val wakeUnlockType by viewModel.wakeUnlockType.collectAsStateWithLifecycle()
    val wakeCredential by viewModel.wakeCredential.collectAsStateWithLifecycle()
    val wakeTestState by viewModel.wakeTestState.collectAsStateWithLifecycle()
    val unlockGesture by viewModel.unlockGesture.collectAsStateWithLifecycle()
    val gestureRecordState by viewModel.gestureRecordState.collectAsStateWithLifecycle()

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val fontSizeScale by viewModel.fontSizeScale.collectAsStateWithLifecycle()
    val useSystemMonetColor by viewModel.useSystemMonetColor.collectAsStateWithLifecycle()
    val backgroundResolution by viewModel.backgroundResolution.collectAsStateWithLifecycle()

    val updateChannel by viewModel.updateChannel.collectAsStateWithLifecycle()
    val autoCheckUpdate by viewModel.autoCheckUpdate.collectAsStateWithLifecycle()
    val autoDownloadUpdate by viewModel.autoDownloadUpdate.collectAsStateWithLifecycle()
    val debugMode by viewModel.debugMode.collectAsStateWithLifecycle()

    var showLogExportSheet by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }

    LogExportController(
        sheetVisible = showLogExportSheet,
        onSheetDismiss = { showLogExportSheet = false },
    )

    // 进设置页补一次手势录制结果回收：VM 可能在锁屏期间被重建
    LaunchedEffect(Unit) {
        viewModel.refreshGestureRecord()
    }

    // 导入导出结果用 Toast 反馈，避免每个调用点都自己弹一遍
    LaunchedEffect(settingsMessage) {
        val message = settingsMessage?.resolve(context).orEmpty()
        if (message.isNotEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearSettingsMessage()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use(viewModel::exportConfig)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use(viewModel::importConfig)
    }

    BackHandler(enabled = showChangelogDialog) { showChangelogDialog = false }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestartDialog,
            title = { Text(stringResource(R.string.settings_import_config_title)) },
            text = { Text(stringResource(R.string.common_confirm_restart)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestart) {
                    Text(stringResource(R.string.common_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestartDialog) {
                    Text(stringResource(R.string.common_restart_later))
                }
            },
        )
    }

    val changelog by viewModel.currentChangelog.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = stringResource(R.string.settings_title)) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ───────────────────────── 打卡目标 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_section_task)) {
                SettingRow(
                    title = stringResource(R.string.settings_title),
                    description = viewModel.targetPackageName,
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_notification),
                    onClick = onNavigateToNotification,
                )
            }

            // ───────────────────────── 定时唤醒解锁 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_wake_gesture_hint)) {
                OptionRowGroup(
                    title = stringResource(R.string.settings_wake_unlock_type),
                    options = listOf(
                        UnlockCredential.TYPE_SWIPE to stringResource(R.string.settings_wake_unlock_type_swipe),
                        UnlockCredential.TYPE_PIN to stringResource(R.string.settings_wake_unlock_type_pin),
                        UnlockCredential.TYPE_GESTURE to stringResource(R.string.settings_wake_unlock_type_gesture),
                    ),
                    selected = wakeUnlockType,
                    onSelect = viewModel::setWakeUnlockType,
                )

                if (wakeUnlockType == UnlockCredential.TYPE_PIN) {
                    SettingRow(
                        title = stringResource(R.string.settings_wake_credential),
                        description = stringResource(R.string.settings_wake_credential_hint),
                    )
                    CredentialInputRow(
                        value = wakeCredential,
                        onChange = viewModel::setWakeCredential,
                    )
                }

                if (wakeUnlockType == UnlockCredential.TYPE_GESTURE) {
                    GestureSection(
                        gesture = unlockGesture,
                        recordState = gestureRecordState,
                        onRecord = viewModel::startGestureRecord,
                        onCancelRecord = viewModel::cancelGestureRecord,
                        onClear = viewModel::clearGesture,
                        onClearState = viewModel::clearGestureRecordState,
                    )
                }

                SettingRow(
                    title = stringResource(R.string.settings_wake_test_button),
                    description = stringResource(R.string.settings_wake_test_hint),
                    onClick = viewModel::runWakeTest,
                )
                wakeTestState?.let { state ->
                    WakeTestResultRow(
                        state = state,
                        onDismiss = viewModel::clearWakeTestResult,
                    )
                }
            }

            // ───────────────────────── 提权后端 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_startup_backend_title)) {
                SettingRow(
                    title = stringResource(R.string.settings_startup_backend_title),
                    description = stringResource(R.string.settings_startup_backend_desc),
                )
                OptionRowGroup(
                    title = null,
                    options = RemoteBackend.entries.map {
                        it to it.name
                    },
                    selected = startupBackend.name,
                    onSelect = { name ->
                        RemoteBackend.entries
                            .firstOrNull { it.name == name }
                            ?.let(viewModel::setStartupBackend)
                    },
                )
                SettingRow(
                    title = stringResource(R.string.settings_skip_shizuku_check),
                    trailing = {
                        Switch(
                            checked = skipShizukuCheck,
                            onCheckedChange = viewModel::setSkipShizukuCheck,
                        )
                    },
                )
            }

            // ───────────────────────── 外观 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_section_display)) {
                OptionRowGroup(
                    title = stringResource(R.string.settings_theme_title),
                    options = listOf(
                        AppSettingsManager.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                        AppSettingsManager.ThemeMode.WHITE to stringResource(R.string.settings_theme_white),
                        AppSettingsManager.ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                        AppSettingsManager.ThemeMode.PURE_DARK to stringResource(R.string.settings_theme_pure_dark),
                    ),
                    selected = themeMode,
                    onSelect = viewModel::setThemeMode,
                )

                OptionRowGroup(
                    title = stringResource(R.string.settings_language_title),
                    options = listOf(
                        AppSettingsManager.AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
                        AppSettingsManager.AppLanguage.ZH to stringResource(R.string.settings_language_zh),
                        AppSettingsManager.AppLanguage.EN to stringResource(R.string.settings_language_en),
                    ),
                    selected = language,
                    onSelect = viewModel::setLanguage,
                )

                FontSizeRow(
                    scale = fontSizeScale,
                    onChange = viewModel::setFontSizeScale,
                )

                SettingRow(
                    title = stringResource(R.string.settings_monet_color_title),
                    description = stringResource(R.string.settings_monet_color_desc),
                    trailing = {
                        Switch(
                            checked = useSystemMonetColor,
                            onCheckedChange = viewModel::setUseSystemMonetColor,
                        )
                    },
                )

                SettingRow(
                    title = stringResource(R.string.settings_background_title),
                    description = stringResource(R.string.settings_background_desc),
                    onClick = onNavigateToWallpaper,
                )

                OptionRowGroup(
                    title = stringResource(R.string.settings_background_resolution_title),
                    description = stringResource(R.string.settings_background_resolution_desc),
                    options = DefaultDisplayConfig.ResolutionPreference.entries.map {
                        it to it.name.removePrefix("P").let { p -> "${p}P" }
                    },
                    selected = backgroundResolution,
                    onSelect = viewModel::setBackgroundResolution,
                )
            }

            // ───────────────────────── 更新 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_section_update)) {
                OptionRowGroup(
                    title = stringResource(R.string.settings_update_channel_title),
                    description = stringResource(R.string.settings_update_channel_desc),
                    options = UpdateChannel.entries.map {
                        it to it.value
                    },
                    selected = updateChannel,
                    onSelect = viewModel::setUpdateChannel,
                )
                SettingRow(
                    title = stringResource(R.string.settings_auto_check_update_title),
                    description = stringResource(R.string.settings_auto_check_update_desc),
                    trailing = {
                        Switch(
                            checked = autoCheckUpdate,
                            onCheckedChange = viewModel::setAutoCheckUpdate,
                        )
                    },
                )
                SettingRow(
                    title = stringResource(R.string.settings_auto_download_update_title),
                    description = stringResource(R.string.settings_auto_download_update_desc),
                    trailing = {
                        Switch(
                            checked = autoDownloadUpdate,
                            onCheckedChange = viewModel::setAutoDownloadUpdate,
                        )
                    },
                )
            }

            // ───────────────────────── 日志与数据 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_section_log)) {
                SettingRow(
                    title = stringResource(R.string.settings_log_history_title),
                    description = stringResource(R.string.settings_log_history_desc),
                    onClick = onNavigateToLogHistory,
                )
                SettingRow(
                    title = stringResource(R.string.settings_log_error_title),
                    description = stringResource(R.string.settings_log_error_desc),
                    onClick = onNavigateToErrorLog,
                )
                SettingRow(
                    title = stringResource(R.string.settings_log_export_title),
                    description = stringResource(R.string.settings_log_export_desc),
                    onClick = { showLogExportSheet = true },
                )
                SettingRow(
                    title = stringResource(R.string.settings_export_config_title),
                    description = stringResource(R.string.settings_export_config_desc),
                    onClick = { exportLauncher.launch("FeishuCheckIn-config.json") },
                )
                SettingRow(
                    title = stringResource(R.string.settings_import_config_title),
                    description = stringResource(R.string.settings_import_config_desc),
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                )
            }

            // ───────────────────────── 其他 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_section_other)) {
                SettingRow(
                    title = stringResource(R.string.settings_debug_mode_title),
                    description = stringResource(R.string.settings_debug_mode_desc),
                    trailing = {
                        Switch(
                            checked = debugMode,
                            onCheckedChange = viewModel::setDebugMode,
                        )
                    },
                )
            }

            // ───────────────────────── 关于 ─────────────────────────
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingRow(
                    title = stringResource(R.string.settings_about_version),
                    description = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                SettingRow(
                    title = stringResource(R.string.settings_about_changelog),
                    onClick = { showChangelogDialog = true },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showChangelogDialog) {
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            title = {
                Text(stringResource(R.string.settings_about_changelog))
            },
            text = {
                Text(changelog?.markdown ?: stringResource(R.string.settings_about_changelog_empty))
            },
            confirmButton = {
                TextButton(onClick = { showChangelogDialog = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

// ───────────────────────────── 内部组件 ─────────────────────────────

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(MaaDesignTokens.Spacing.lg))
        SectionHeader(title = title)
        content()
    }
}

/** 单选组：传 null 标题即为「无标题续行」 */
@Composable
private fun <T> OptionRowGroup(
    title: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            SettingRow(title = title, description = description)
        } else if (description != null) {
            SettingRow(title = "", description = description)
        }
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = value == selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    )
                    .padding(
                        start = MaaDesignTokens.Spacing.md,
                        top = MaaDesignTokens.Spacing.xs,
                        bottom = MaaDesignTokens.Spacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = value == selected,
                    onClick = null,
                )
                Spacer(modifier = Modifier.height(0.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = MaaDesignTokens.Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun CredentialInputRow(
    value: String,
    onChange: (String) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.sm),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = local,
            onValueChange = { local = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_wake_credential)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
            ),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
    }
    LaunchedEffect(local) {
        if (local != value) onChange(local)
    }
}

@Composable
private fun FontSizeRow(
    scale: Int,
    onChange: (Int) -> Unit,
) {
    var local by remember(scale) { mutableIntStateOf(scale) }
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            title = stringResource(R.string.settings_font_size_title),
            description = stringResource(R.string.settings_font_size_summary),
        )
        Slider(
            value = local.toFloat(),
            onValueChange = { local = it.toInt() },
            onValueChangeFinished = { onChange(local) },
            valueRange = 80f..140f,
            steps = 11,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GestureSection(
    gesture: UnlockGesture?,
    recordState: SettingsViewModel.GestureRecordState?,
    onRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onClear: () -> Unit,
    onClearState: () -> Unit,
) {
    val summary = when {
        gesture == null -> stringResource(R.string.settings_wake_gesture_none)
        else -> stringResource(R.string.settings_wake_gesture_done, gesture.steps.size)
    }
    SettingRow(
        title = stringResource(R.string.settings_wake_gesture_record),
        description = summary,
    )

    when (recordState) {
        SettingsViewModel.GestureRecordState.Preparing -> GestureHintRow(
            text = stringResource(R.string.settings_wake_gesture_preparing),
        )

        SettingsViewModel.GestureRecordState.Recording -> {
            GestureHintRow(text = stringResource(R.string.settings_wake_gesture_waiting))
            TextButton(onClick = onCancelRecord, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_wake_gesture_cancel))
            }
        }

        is SettingsViewModel.GestureRecordState.Done -> GestureHintRow(
            text = stringResource(R.string.settings_wake_gesture_done, recordState.steps),
        )

        is SettingsViewModel.GestureRecordState.Failed -> {
            GestureHintRow(text = recordState.result.message.asString())
            TextButton(onClick = onClearState, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_confirm))
            }
        }

        null -> {}
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Button(
            onClick = onRecord,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(
                    if (gesture == null) {
                        R.string.settings_wake_gesture_record
                    } else {
                        R.string.settings_wake_gesture_rerecord
                    }
                )
            )
        }
        if (gesture != null) {
            TextButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_wake_gesture_clear))
            }
        }
    }

    GestureStepsPreview(gesture)
}

@Composable
private fun GestureHintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.xs),
    )
}

@Composable
private fun GestureStepsPreview(gesture: UnlockGesture?) {
    if (gesture == null) return
    Column(modifier = Modifier.fillMaxWidth()) {
        gesture.steps.forEachIndexed { index, step ->
            val label = when (step) {
                is UnlockStep.Tap -> stringResource(R.string.settings_wake_gesture_step_tap)
                is UnlockStep.LongPress -> stringResource(R.string.settings_wake_gesture_step_long_press)
                is UnlockStep.Swipe -> stringResource(R.string.settings_wake_gesture_step_swipe)
            }
            Text(
                text = "${index + 1}. $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WakeTestResultRow(
    state: SettingsViewModel.WakeTestState,
    onDismiss: () -> Unit,
) {
    val text = when (state) {
        SettingsViewModel.WakeTestState.Testing -> stringResource(R.string.settings_wake_gesture_waiting)
        is SettingsViewModel.WakeTestState.Done -> state.result.message.asString()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state is SettingsViewModel.WakeTestState.Done) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_confirm))
            }
        }
    }
}
