package com.aliothmoon.maameow.constant

/**
 * 打卡目标应用包名常量。
 *
 * 飞书打卡版只服务一个目标应用：飞书（Lark）。
 * 海外版为 com.larksuite.suite，国内版为 com.ss.android.lark，这里以国内版为准。
 */
const val FEISHU_PACKAGE_NAME: String = "com.ss.android.lark"

/** 飞书国内版的几个常见 Activity 入口，供无障碍兜底跳转使用 */
const val FEISHU_MAIN_ACTIVITY: String = "com.ss.android.lark.main.app.MainActivity"

/** 按包名返回可读的应用名，用于界面展示 */
fun targetAppLabel(packageName: String): String = when (packageName) {
    FEISHU_PACKAGE_NAME -> "飞书"
    "com.larksuite.suite" -> "飞书 (Lark)"
    else -> packageName
}
