package com.feishu.checkin.core.shizuku

import android.content.pm.PackageManager
import timber.log.Timber

/**
 * Shizuku 可用性检查。
 *
 * ## Shizuku 是什么，以及它为什么有用
 *
 * Shizuku 让普通应用**以 adb/root 身份调用系统 API**。
 * 原理是它先以 adb 权限启动一个常驻进程，应用通过 binder 把请求
 * 转发给这个进程去执行 —— 所以应用本身不需要 root。
 *
 * 对打卡场景，它补的正是无障碍最弱的两块：
 *
 * ### 1. 落点校验（最有价值）
 *
 * 无障碍只能看到**当前应用**的界面节点。判断「深链跳转后是否落在
 * 考勤页」时，只能靠界面文案猜 —— 但飞书首页和考勤页都有「打卡」类
 * 文案，猜错的代价是**在错误页面上乱点**。
 *
 * 而 `dumpsys activity activities` 能直接读到当前前台 Activity 的
 * 完整类名。`com.ss.android.lark.attendance.AttendanceActivity` 这种
 * 名字是不会骗人的 —— 这比任何文案匹配都硬。
 *
 * ### 2. 启动的确定性
 *
 * `am start` 走系统服务而不是应用侧 Intent 解析，
 * 可以用 `-n` 显式指定组件、用 `-p` 锁定包名，
 * 不受「多个应用都注册了同一 scheme」的干扰。
 *
 * ## 硬边界（必须清楚）
 *
 * Shizuku **拿不到别的应用的界面内容**。`dumpsys` 能看到窗口、
 * Activity、Service 这些**系统层面的元信息**，但看不到飞书内部的
 * 控件树 —— 那是无障碍的领域。
 *
 * 所以两者是互补而不是替代：Shizuku 管「在哪」，无障碍管「有什么」。
 * 点打卡按钮这一步**永远需要无障碍**（或盲点坐标，极不可靠）。
 */
object ShizukuSupport {

    /**
     * Shizuku 的包名。
     *
     * 注意有两个：开发版与正式版包名不同，必须都识别，
     * 否则用户装了开发版会被判定为「未安装」。
     */
    private const val PACKAGE_RELEASE = "moe.shizuku.privileged.api"
    private const val PACKAGE_DEV = "moe.shizuku.privileged.api.dev"

    /**
     * Shizuku 提供给第三方应用的权限名。
     *
     * 应用需要在 Manifest 里声明它，并在运行时用
     * `Shizuku.requestPermission()` 请求用户授权。
     */
    const val PERMISSION = "moe.shizuku.manager.permission.API_V23"

    /**
     * 检查 Shizuku 是否可用（已安装 + 服务运行中 + 已授权）。
     *
     * 用**反射**调用 Shizuku 的 API，而不是编译期依赖它的 SDK。
     *
     * 为什么这样做：
     * 1. 编译期依赖会把这个库打进 APK，而绝大多数用户没装 Shizuku，
     *    等于让所有人替少数人多背一份代码和体积
     * 2. Shizuku 的 API 在不同版本间有变更，编译期绑定会在
     *    用户装了旧版 Shizuku 时直接抛 NoSuchMethodError
     * 3. 反射失败可以优雅降级 —— 而这是本项目的核心设计原则：
     *    **Shizuku 是可选增强，缺了它必须还能跑**
     *
     * 代价是失去编译期检查。为此 catch 了所有异常，
     * 任何一步失败都当作「不可用」处理，绝不向上抛。
     */
    fun isAvailable(packageManager: PackageManager): Boolean {
        if (!isInstalled(packageManager)) {
            Timber.d("Shizuku 未安装")
            return false
        }

        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            // Shizuku.pingBinder() —— 服务是否活着
            val ping = shizukuClass
                .getMethod("pingBinder")
                .invoke(null) as? Boolean ?: false
            if (!ping) {
                Timber.d("Shizuku 已安装但服务未运行（需要先在 Shizuku 应用里启动服务）")
                return false
            }

            // Shizuku.checkSelfPermission() —— 本应用是否已获授权
            val granted = shizukuClass
                .getMethod("checkSelfPermission")
                .invoke(null) as? Int == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Timber.d("Shizuku 已运行但本应用未获授权")
            }
            granted
        } catch (t: Throwable) {
            // ClassNotFoundException / NoSuchMethodError / 各种反射异常
            // 统一视为不可用 —— 这是可选能力，不能拖垮主流程
            Timber.w(t, "Shizuku 可用性检查失败，按不可用处理")
            false
        }
    }

    /** Shizuku 应用是否已安装（不看服务是否运行） */
    fun isInstalled(packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(PACKAGE_RELEASE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            try {
                packageManager.getPackageInfo(PACKAGE_DEV, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * 当前 Shizuku 是以什么身份运行的。
     *
     * 这个信息对排障很重要：adb 身份与 root 身份的权限集**不同**，
     * 某些命令在 adb 下会静默失败或输出不完整。
     * 用户报「探测不到东西」时，先看这个。
     */
    fun describeRuntime(packageManager: PackageManager): String {
        if (!isInstalled(packageManager)) return "未安装"
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val ping = shizukuClass.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!ping) return "已安装，服务未运行"

            val uid = shizukuClass.getMethod("getUid").invoke(null) as? Int ?: -1
            val granted = shizukuClass
                .getMethod("checkSelfPermission")
                .invoke(null) as? Int == PackageManager.PERMISSION_GRANTED

            buildString {
                append(if (uid == 0) "root 身份" else "adb 身份 (uid=$uid)")
                append(if (granted) "，已授权" else "，未授权")
            }
        } catch (t: Throwable) {
            "状态未知（${t.javaClass.simpleName}）"
        }
    }
}
