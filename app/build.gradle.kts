import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // ⚠️ 不要加 kotlin.android：AGP 9.0+ 已内置 Kotlin 支持，
    //    重复应用会导致构建直接失败
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.feishu.checkin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.feishu.checkin"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // 飞书官方包名，供无障碍服务过滤与拉起使用
        buildConfigField("String", "FEISHU_PACKAGE", "\"com.ss.android.lark\"")
        // 飞书极速版（部分企业用这个）
        buildConfigField("String", "FEISHU_PACKAGE_LITE", "\"com.larksuite.suite\"")
    }

    signingConfigs {
        create("release") {
            // KEYSTORE_PATH 按「仓库根相对路径」理解（如 keystore/release.jks）。
            // 必须用 rootProject.file()：直接用 file() 会以 app 模块为基准。
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = rootProject.file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // 排障开关：MAA_NO_R8=true 时产出不混淆的 release 包，用于 A/B 对照
            val noR8 = System.getenv("MAA_NO_R8")?.toBoolean() == true
            isMinifyEnabled = !noR8
            isShrinkResources = !noR8
            if (noR8) {
                println("[R8] MAA_NO_R8=true —— release 关闭 minify/shrink（排障用）")
            } else {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
                println("[Signing] Using keystore: $keystorePath")
            } else {
                println("[Signing] No keystore configured — release 将产出未签名包")
            }
        }
    }

    // 单元测试配置。
    //
    // ## 关于「让 release 变体也跑单测」（走过的弯路）
    //
    // 最初的设想是开启 :app:testReleaseUnitTest，好在 R8 混淆后的产物上测，
    // 以捕获「混淆导致反射失效」这类只在 release 出现的问题。
    // 实测这条路走不通，原因是 API 层面的：
    //
    //   - `enableUnitTest` 属性不在 `VariantBuilder` 上，
    //     而在 `HasUnitTestBuilder` 接口上
    //   - 该接口**只存在于 gradle-api-9.2.1.jar**，
    //     不在构建脚本 classpath 的 gradle-9.2.1.jar 里
    //   - 结果是无论怎么强制类型转换，Kotlin DSL 都报
    //     `Unresolved reference 'enableUnitTest'`
    //     （甚至先说 "Check for instance is always 'true'" 再报未解析，很迷惑）
    //
    // 既然如此，CI 改跑 :app:testDebugUnitTest —— 它存在、且当前全绿。
    // 放弃 release 变体单测的损失很小：
    //   - 单测覆盖的是纯 JVM 逻辑（时间计算、结果语义），本来就不涉及混淆
    //   - R8 的真实风险由 proguard-rules.pro 的 keep 规则 + 真机验证来兜
    // 为了一个「锦上添花」的测试任务去塞 buildscript 依赖，不划算。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }

    lint {
        abortOnError = false
    }
}

// ── 无意开启 release 变体的单元测试 ──
//
// 曾经尝试在这里用 androidComponents { beforeVariants { ... } }
// 打开 release 的 unitTest 变体，让 CI 能跑 :app:testReleaseUnitTest。
// 实测失败，原因见 android { testOptions } 上方的注释：
// `enableUnitTest` 所属的 `HasUnitTestBuilder` 接口不在构建脚本 classpath 上。
//
// CI 因此改用 :app:testDebugUnitTest。

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.splashscreen)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // 存储
    implementation(libs.androidx.datastore.preferences)

    // 日志 / 序列化
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
