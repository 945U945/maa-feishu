package com.aliothmoon.maameow.koin

import com.aliothmoon.maameow.presentation.viewmodel.ErrorLogViewModel
import com.aliothmoon.maameow.presentation.viewmodel.HomeViewModel
import com.aliothmoon.maameow.presentation.viewmodel.LogHistoryViewModel
import com.aliothmoon.maameow.presentation.viewmodel.NotificationSettingsViewModel
import com.aliothmoon.maameow.presentation.viewmodel.ProbeViewModel
import com.aliothmoon.maameow.presentation.viewmodel.SettingsViewModel
import com.aliothmoon.maameow.schedule.ui.ScheduleEditViewModel
import com.aliothmoon.maameow.schedule.ui.ScheduleListViewModel
import com.aliothmoon.maameow.schedule.ui.ScheduleTriggerLogViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * ViewModel 装配。
 *
 * 原工程中的游戏相关 ViewModel（干员箱、抄作业、工具箱、成就等）
 * 已全部移除，仅保留通用页面与定时任务三件套。
 */
val viewModelModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::ProbeViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::LogHistoryViewModel)
    viewModelOf(::ErrorLogViewModel)
    viewModelOf(::ScheduleListViewModel)
    viewModelOf(::ScheduleEditViewModel)
    viewModelOf(::ScheduleTriggerLogViewModel)
    viewModelOf(::NotificationSettingsViewModel)
}
