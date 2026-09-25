package com.aliothmoon.maameow.constant

/**
 * 应用级常量。
 *
 * 原 MAA-Meow 的 `MaaApi` 指向 MaaAssistantArknights 的游戏资源与公告接口，
 * 打卡版不再需要；这里只保留反馈入口等与业务无关的少量常量。
 */
object AppApi {
    /** 问题反馈地址 */
    const val FEEDBACK_URL = "https://github.com/Aliothmoon/MAA-Meow/issues"
}

/**
 * 提权进程侧的目录名常量。
 *
 * 对应原 `MaaFiles`，但去掉了资源包、缓存、截图等游戏专有项。
 */
object RemoteDirs {
    /** 提权进程 debug 目录名 */
    const val DEBUG = "debug"

    /** 独立数据目录模式下，提权进程 debug/ 在日志包里的目录名 */
    const val EXPORT_REMOTE_DIR = "remote"
}
