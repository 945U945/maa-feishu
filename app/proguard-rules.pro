# 只锁按名查找的入口

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# JNI RegisterNatives
-keep class com.aliothmoon.maameow.bridge.NativeBridgeLib {
    native <methods>;
}

# libbridge FindClass + GetStaticMethodID
-keep class com.aliothmoon.maameow.maa.DriverClass {
    public static boolean startApp(java.lang.String, int, boolean);
    public static boolean touchDown(int, int, int, int);
    public static boolean touchMove(int, int, int, int);
    public static boolean touchUp(int, int, int, int);
    public static boolean keyDown(int, int);
    public static boolean keyUp(int, int);
}

# JNA：方法名即 C 符号；嵌套 Callback 整包留
-keep class com.aliothmoon.maameow.maa.** { *; }
# libjnidispatch 只 FindClass 顶层；ptr/internal/win32 交给 R8
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }
-keepclassmembers class * implements com.sun.jna.Callback { <methods>; }
-dontwarn java.awt.**

# Shizuku / Root 按类名拉起
-keep class com.aliothmoon.maameow.remote.RemoteServiceImpl { <init>(); }
-keep class com.aliothmoon.maameow.remote.LogcatCaptureServiceImpl { <init>(); }
-keep class com.aliothmoon.maameow.root.RemoteServiceStarter {
    public static void main(java.lang.String[]);
}
-keep class com.aliothmoon.maameow.root.RootUserService { *; }
-keep class com.aliothmoon.maameow.root.RootServiceBootstrapProvider { *; }

# AIDL
-keep class com.aliothmoon.maameow.RemoteService { *; }
-keep class com.aliothmoon.maameow.RemoteService$Stub { *; }
-keep class com.aliothmoon.maameow.ILogcatService { *; }
-keep class com.aliothmoon.maameow.ILogcatService$Stub { *; }
-keep class com.aliothmoon.maameow.ITouchEventCallback { *; }
-keep class com.aliothmoon.maameow.ITouchEventCallback$Stub { *; }
-keep class com.aliothmoon.maameow.remote.PermissionGrantRequest { *; }
-keep class com.aliothmoon.maameow.remote.PermissionStateInfo { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# SMTP：ServiceLoader / mailcap 点名的实现，imap/pop3 缺一个就炸
-keep class org.eclipse.angus.mail.smtp.** { *; }
-keep class org.eclipse.angus.mail.imap.** { *; }
-keep class org.eclipse.angus.mail.pop3.** { *; }
-keep class org.eclipse.angus.mail.handlers.** { *; }
-keep class org.eclipse.angus.mail.util.MailStreamProvider { *; }
-keep class org.eclipse.angus.activation.*RegistryProviderImpl { *; }
-dontwarn org.eclipse.angus.**
-dontwarn jakarta.**
-dontwarn javax.**

# 任务覆盖编辑器：Gson 解 language-configuration；OnigRegExp 依赖 joni 静态初始化
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.joni.**
-dontwarn org.jcodings.**

# FakeContext / ShellContentResolver：acquireProvider 等对编译期不可见，R8 当死代码删掉
-keep class com.aliothmoon.maameow.third.FakeContext { *; }
-keep class com.aliothmoon.maameow.third.FakeContext$* { *; }
-keepclassmembers class * extends android.content.ContentResolver {
    *** acquireProvider(...);
    *** acquireUnstableProvider(...);
    *** releaseProvider(...);
    *** releaseUnstableProvider(...);
    *** unstableProviderDied(...);
}

# xzakota 焦点/超级岛模板靠 kotlinx.serialization 反射字段
-keep class com.xzakota.hyper.notification.** { *; }

# 落盘 Enum.name / valueOf
-keepclassmembers enum com.aliothmoon.maameow.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ══════════════════════════════════════════════════════════════
#  打卡版新增：Koin / Compose / 反射相关的保留规则
#
#  注意：下面这批是原游戏版 proguard 规则的补漏。Koin 通过「类型 token」
#  在运行期解析依赖，R8 若把类型改名或把 ViewModel 的无参构造删掉，
#  表现就是「编译通过、启动即闪退」——极难定位，必须显式 keep。
# ══════════════════════════════════════════════════════════════

# ── Koin ──
# Koin 的 module DSL 会生成引用各实现类构造的 lambda，R8 可能判定为死代码。
-keep class org.koin.** { *; }
-dontwarn org.koin.**
# KoinComponent 的 by inject() 依赖运行期解析
-keepclassmembers class * implements org.koin.core.component.KoinComponent {
    <fields>;
    <methods>;
}
# @KoinViewModel 等注解保留（Koin 靠注解扫描）
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep,allowobfuscation @interface org.koin.core.annotation.*

# ── 所有 ViewModel：Koin 按类型取，且 ViewModelProvider 需要公开构造 ──
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── Application / Activity / Service / Receiver / Provider ──
# 这些是 manifest 注册的组件，名字不能改（改了系统找不到）
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# ── 本工程自有类：注册的组件与入口一律保留原名 ──
# 打卡版大量使用 Koin 按类型解析 + 无障碍服务按类名拉起，
# 一旦被改名，系统或 Koin 都会找不到。
-keep class com.aliothmoon.maameow.MaaApplication { *; }
-keep class com.aliothmoon.maameow.MainActivity { *; }
-keep class com.aliothmoon.maameow.service.** { *; }
-keep class com.aliothmoon.maameow.schedule.receiver.** { *; }
-keep class com.aliothmoon.maameow.utils.AppBootTrace { *; }
-keep class com.aliothmoon.maameow.utils.CrashHandler { *; }

# ── Compose ──
# Compose 的 @Composable 由编译器插件改写，R8 过度优化会破坏调用约定
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
# Compose 的 SnapshotState 反射访问
-keepclassmembers class androidx.compose.runtime.snapshots.SnapshotStateList { *; }
-keepclassmembers class androidx.compose.runtime.snapshots.SnapshotStateMap { *; }

# ── androidx.lifecycle / 序列化 ──
-keep class androidx.lifecycle.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aliothmoon.maameow.**$$serializer { *; }
-keepclassmembers class com.aliothmoon.maameow.** {
    *** Companion;
}

# ── Timber ──
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# ── Kotlin 元数据（反射读 Kotlin 类型信息时需要）──
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ── OkHttp / Retrofit 类库常见的反射点 ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

