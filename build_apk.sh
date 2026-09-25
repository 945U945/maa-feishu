#!/usr/bin/env bash
# 飞书打卡助手 —— 一键编译脚本
#
# 用法：
#   ./build_apk.sh              # 编 debug 包（可直接装）
#   ./build_apk.sh release      # 编 release 包（需签名配置）
#   ./build_apk.sh release all  # release + 全 ABI
#
# Windows 请在 Git Bash 下运行；或直接用 gradlew.bat。

set -euo pipefail

VARIANT="${1:-debug}"
ABI="${2:-arm64-v8a}"

cd "$(dirname "$0")"

# ── 环境自检 ──────────────────────────────────────────────
echo "==> 检查 JDK"
if ! command -v java >/dev/null 2>&1; then
    echo "✗ 未找到 java。需要 JDK 17。"
    exit 1
fi
JAVA_RAW="$(java -version 2>&1 | head -1)"
echo "    $JAVA_RAW"
# 兼容 "1.8.0_151" 与 "17.0.13" 两种版本串
JAVA_VER="$(echo "$JAVA_RAW" | sed -E 's/.*version "([0-9]+)(\.([0-9]+))?.*/\1/')"
JAVA_MINOR="$(echo "$JAVA_RAW" | sed -E 's/.*version "[0-9]+\.([0-9]+).*/\1/')"
if [ "$JAVA_VER" = "1" ]; then
    JAVA_VER="$JAVA_MINOR"   # 1.8 -> 8
fi
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    echo "✗ AGP 9.2.1 要求 JDK 17+，当前是 JDK $JAVA_VER。"
    echo "  装 JDK 17 后，在 local.properties 里加：org.gradle.java.home=<JDK17 路径>"
    exit 1
fi

echo "==> 检查 Android SDK"
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "✗ 未找到 Android SDK。"
    echo "  请安装 Android SDK，并在 local.properties 写：sdk.dir=<SDK 路径>"
    exit 1
fi
echo "    local.properties: $([ -f local.properties ] && echo 存在 || echo 缺失)"
echo "    ANDROID_HOME: ${ANDROID_HOME:-未设置}"

# ── 静态自检（可选）───────────────────────────────────────
if command -v python >/dev/null 2>&1 && [ -f scripts/check_strings.py ]; then
    echo "==> 静态自检"
    python scripts/check_strings.py app/src/main || {
        echo "✗ 字符串资源校验未通过，先修掉再编译。"
        exit 1
    }
fi

# ── 编译 ────────────────────────────────────────────────
GRADLE="./gradlew"
[ -f "$GRADLE" ] || GRADLE="./gradlew.bat"
chmod +x ./gradlew 2>/dev/null || true

case "$VARIANT" in
    debug)
        echo "==> 编译 Debug APK (abi=$ABI)"
        $GRADLE clean assembleDebug -Pmaa.abi="$ABI" --stacktrace
        OUT="app/build/outputs/apk/debug"
        ;;
    release)
        echo "==> 编译 Release APK (abi=$ABI)"
        $GRADLE clean assembleRelease -Pmaa.abi="$ABI" --stacktrace
        OUT="app/build/outputs/apk/release"
        ;;
    *)
        echo "✗ 未知变体：$VARIANT（可选 debug / release）"
        exit 1
        ;;
esac

# ── 结果 ────────────────────────────────────────────────
echo
echo "==> 产物"
if ls "$OUT"/*.apk >/dev/null 2>&1; then
    ls -lh "$OUT"/*.apk
    echo
    echo "装入手机：adb install -r $OUT/<文件名>.apk"
else
    echo "✗ 未生成 apk，请检查上面的错误输出。"
    exit 1
fi
