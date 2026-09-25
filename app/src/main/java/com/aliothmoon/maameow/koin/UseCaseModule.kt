package com.aliothmoon.maameow.koin

import com.aliothmoon.maameow.domain.checkin.CollectNodeTreeUseCase
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import timber.log.Timber

/**
 * 用例层装配。
 *
 * 原工程的三个游戏用例（分析任务链 / 检查游戏就绪 / 准备任务启动）
 * 已全部移除，替换为打卡相关的用例。
 */
val useCaseModule = module {
    /**
     * 控件树采集用例。
     *
     * 这是「控件探针」功能的核心：导出当前界面的控件结构，
     * 用户可据此精确配置打卡规则。
     * 之所以做成用例而非直接调服务，是因为它需要
     * 检查无障碍状态、处理异常、组织输出格式，属于业务编排。
     */
    factory {
        CollectNodeTreeUseCase(
            isAccessibilityReady = {
                com.aliothmoon.maameow.service.CheckInAccessibilityService.isReady()
            },
            dumpTree = {
                com.aliothmoon.maameow.service.CheckInAccessibilityService.instance
                    ?.dumpTree() ?: emptyList()
            },
            currentPackage = {
                com.aliothmoon.maameow.service.CheckInAccessibilityService.instance?.currentPackage
            },
            isPackageInstalled = { packageName ->
                withContext(Dispatchers.IO) {
                    try {
                        RemoteServiceManager.getInstanceOrNull()
                            ?.isPackageInstalled(packageName) ?: false
                    } catch (e: Exception) {
                        Timber.w(e, "检查包名安装状态失败：%s", packageName)
                        false
                    }
                }
            },
            appContext = androidContext(),
        )
    }
}
