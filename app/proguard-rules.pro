# ══════════════════════════════════════════════════════════════
#  FeishuCheckIn ProGuard / R8 规则
#
#  注意：本项目使用 Koin（运行期按类型解析依赖）与 Compose。
#  这两者都对 R8 的改名/裁剪敏感，一旦规则缺失，典型症状是
#  「编译通过、安装后启动即闪退」——极难定位。因此下述 keep
#  规则不是可选项。
# ══════════════════════════════════════════════════════════════

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ── 应用入口与 manifest 组件（系统按类名查找，改名即失效）──
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService

-keep class com.feishu.checkin.FeishuCheckInApp { *; }
-keep class com.feishu.checkin.MainActivity { *; }
-keep class com.feishu.checkin.accessibility.** { *; }
-keep class com.feishu.checkin.checkin.receiver.** { *; }
-keep class com.feishu.checkin.checkin.service.** { *; }
-keep class com.feishu.checkin.core.log.** { *; }

# ── Koin ──
-keep class org.koin.** { *; }
-dontwarn org.koin.**
-keepclassmembers class * implements org.koin.core.component.KoinComponent {
    <fields>;
    <methods>;
}

# ── ViewModel：Koin 按类型取，且需要公开构造 ──
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# ── Compose ──
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# ── androidx.lifecycle ──
-keep class androidx.lifecycle.** { *; }

# ── kotlinx.serialization（JSON 方案持久化靠它）──
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 被 @Serializable 标注的数据类的合成 serializer 必须保留
-keep,includedescriptorclasses class com.feishu.checkin.**$$serializer { *; }
-keepclassmembers class com.feishu.checkin.** {
    *** Companion;
}
-keepclasseswithmembers class com.feishu.checkin.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── 数据模型：反射/序列化读写字段名 ──
-keep class com.feishu.checkin.checkin.model.** { *; }

# ── Timber ──
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# ── Kotlin 元数据 ──
-keep class kotlin.Metadata { *; }

# ── 枚举：方案里按 name 持久化，valueOf 不能被裁 ──
-keepclassmembers enum com.feishu.checkin.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── 保留行号，崩溃堆栈才有意义 ──
-keepclassmembers class * {
    *** getStackTrace(...);
}
