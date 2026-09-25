// ══════════════════════════════════════════════════════════════
//  根项目构建脚本
//
//  ⚠️ 关于 Kotlin 插件的重要说明（AGP 9.0+ 变更）
//
//  AGP 9.0 起**内置了 Kotlin 支持**，不再需要单独应用
//  `org.jetbrains.kotlin.android` 插件。继续应用它会直接报错：
//
//    The 'org.jetbrains.kotlin.android' plugin is no longer
//    required for Kotlin support since AGP 9.0.
//
//  因此这里**只声明** kotlin.android 而不 apply，也不在 app 模块里
//  使用它 —— 保留在 libs.versions.toml 里是为了让 Kotlin 版本号
//  有唯一来源，供 compose / serialization 插件对齐。
//
//  这是一处历史踩坑：按 AGP 8.x 的写法配置在 AGP 9 上会直接
//  构建失败，且错误信息指向插件而非版本配置，不太直观。
// ══════════════════════════════════════════════════════════════

plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin 支持由 AGP 内置，此处**不应用** kotlin.android
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
