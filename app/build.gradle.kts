import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    id("com.aliothmoon.maameow.i18n-verify")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val gitVersionCode: Int by lazy {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

val gitVersionName: String by lazy {
    val desc = providers.exec {
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(
            desc
        )
    if (match != null) {
        val (major, minor, patch, pre, distance) = match.destructured
        when {
            distance.isEmpty() && pre.isEmpty() -> "$major.$minor.$patch"
            distance.isEmpty() -> "$major.$minor.$patch-$pre"
            else -> "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
        }
    } else {
        desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    }
}

// 本机默认只编 arm64；CI=true 保持双 ABI
// -Pmaa.abi=all|arm64-v8a|x86_64 或 local.properties 覆盖
val ci = System.getenv("CI")?.equals("true", ignoreCase = true) == true
val abiRaw = (findProperty("maa.abi") as String?)?.trim().orEmpty()
    .ifEmpty { localProperties.getProperty("maa.abi", "").trim() }
    .ifEmpty { if (ci) "all" else "arm64-v8a" }
val nativeAbis: List<String> = if (abiRaw.equals("all", ignoreCase = true)) {
    listOf("arm64-v8a", "x86_64")
} else {
    abiRaw.split(',', ' ').map(String::trim).filter(String::isNotEmpty)
}
println("[ABI] ${nativeAbis.joinToString()}")
println("[Java Version] ${System.getProperty("java.version")}")

android {
    namespace = "com.aliothmoon.maameow"
    compileSdk = 37


    defaultConfig {
        applicationId = "com.feishu.checkin"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
        println("Build version: versionCode=$versionCode, versionName=$versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 无 native 代码（MaaCore 已剥离），仅保留 arm64 作为默认 ABI 声明
        ndk {
            abiFilters.addAll(nativeAbis)
        }
    }

    signingConfigs {
        create("release") {
            // KEYSTORE_PATH 统一按「仓库根相对路径」理解（如 release.jks）。
            // 这里必须用 rootProject.file()：直接用 file() 会以 app 模块为基准，
            // 传 "release.jks" 会被解析成 app/release.jks，两个基准混用极易踩坑。
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
        val minifyProguardFiles = listOf(
            getDefaultProguardFile("proguard-android-optimize.txt").absolutePath,
            "proguard-rules.pro",
        )
        // local.properties: maa.debugR8=true 时 debug 也走 R8
        val debugR8 = localProperties.getProperty("maa.debugR8", "false").toBoolean()
        getByName("debug") {
            isMinifyEnabled = debugR8
            isShrinkResources = debugR8
            if (debugR8) {
                proguardFiles(*minifyProguardFiles.toTypedArray())
                println("[R8] debug minify+shrink enabled (maa.debugR8=true)")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(*minifyProguardFiles.toTypedArray())
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
                println("[Signing] Using release keystore: $keystorePath")
            } else {
                println("[Signing] No release keystore configured, release build will not be signed")
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            pickFirsts += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters += listOf("zh", "en")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":hidden-api"))
    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Third-party
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.fastjson2)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    implementation(libs.device.compat)
    implementation(libs.xx.permissions)
    implementation(libs.floatingx)
    implementation(libs.sonner)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    implementation(libs.reorderable)
    implementation(libs.compose.markdown)

    // sora-editor：JSON 语法高亮编辑器（TextMate + darcula 主题）
    implementation(platform(libs.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xzakota.focus.api)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
