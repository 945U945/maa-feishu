<div align="center">

# 飞书打卡助手 (FeishuCheckIn)

基于 [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) 改造的**定时自动打卡工具** —— 目标应用由《明日方舟》替换为**飞书**，游戏相关内容已全部剔除。

</div>

---

## 它做什么

到点自动唤醒手机 → 解锁 → 打开飞书 → 走考勤打卡流程 → 记录结果并通知你。

核心能力分三层：

| 层 | 负责 | 实现 |
| --- | --- | --- |
| **调度** | 什么时候打卡 | `ScheduleAlarmManager`（精确闹钟）→ `ScheduleTriggerHandler` → `ScheduleExecutionService` |
| **管线** | 设备准备好了没 | `LaunchPipeline`：唤醒亮屏 → 解锁 → 倒计时 → 拉起目标应用 |
| **执行** | 具体怎么打卡 | `CheckInRunner` + `CheckInEngine`（无障碍定位控件 → 图像模板兜底） |

职责刻意分离：将来要支持新的打卡目标（钉钉、企业微信），只需新增一套 `CheckInProfile` 规则，调度层与管线完全不用动。

---

## 为什么飞书走无障碍

飞书开启 `FLAG_SECURE`（防截屏），系统会拒绝任何截屏请求 —— 这是 Android 的安全边界，无法绕过。因此：

- **图像模板识别**对飞书**物理不可行**（截出来是黑屏）。
- **无障碍服务**不受防截屏影响，可以直接读取控件树并执行点击。

所以本工程采用**双引擎**设计：

- `ACCESSIBILITY` —— 读控件树的 `text` / `viewId` / `contentDesc` 定位，`ACTION_CLICK` 点击。**飞书走这条**。
- `IMAGE_TEMPLATE` —— 灰度降采样 + 逐像素绝对差匹配（不依赖 OpenCV）。留给没有防截屏的目标应用作兜底。

### 控件探针

既然截不了图，怎么知道飞书界面长什么样？

App 内置了 **「控件探针」** 页签（`ProbeView`）：它用无障碍服务直接把当前屏幕的**控件树**导出成结构化文本 —— 包含每个节点的 `text`、`viewId`、`contentDesc`、`bounds`、`clickable`。

把它导出后，就能据此精确编写/修正打卡规则，不再依赖截图。

---

## 编译成 APK

### 一句话选路线

| 你的情况 | 走哪条 | 耗时 |
| --- | --- | --- |
| 没有 Android 开发环境 / 不想装几个 G 的 SDK | **路线 A：GitHub Actions 云端构建** | 约 5–10 分钟 |
| 已经装了 Android Studio | **路线 B：本地命令行** | 首次约 10–20 分钟 |

要求：**JDK 17**、**Android SDK**（compileSdk 37 / targetSdk 36 / minSdk 28）。**无需 NDK** —— MaaCore 原生代码已全部移除。

> ⚠️ JDK 8 / 11 编不了（AGP 9.2.1 要求 17+）。

---

### 路线 A：GitHub Actions 云端构建（推荐）

不需要在本机装任何东西，构建机自带 JDK 17 与 Android SDK。

**A-1. 把工程推到一个 GitHub 仓库**

```bash
cd FeishuCheckIn
git init
git add -A
git commit -m "feat: 飞书打卡助手"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

**A-2. 两种出包方式，任选**

**方式一：推 tag 自动出包（最省事）**

```bash
git tag v1.0.0
git push origin v1.0.0
```

推送后自动跑 `build-release.yml`，产出三个 ABI 的包并**直接挂到 GitHub Release**上，进仓库的 Releases 页面就能下载。

**方式二：手动触发（想先只出 debug 包试水）**

进仓库 → **Actions** 标签页 → 左侧选工作流：

- **Build Dev APK** → 右侧 `Run workflow` → 出 debug 包
- **Build Release APK** → 右侧 `Run workflow` → 出 release 包

跑完后在该次运行的页面底部 **Artifacts** 区下载（是一个 zip，解开就是 apk）。

**A-3.（可选）配置签名**

不配也能出包，只是 release 包未签名、无法覆盖安装不同构建的版本。要签名就在仓库 **Settings → Secrets and variables → Actions** 添加四个 Secret：

| Secret 名 | 值 |
| --- | --- |
| `KEYSTORE_PATH` | keystore 文件**在构建机上的绝对路径** |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

`KEYSTORE_PATH` 需要文件真实存在于构建机上，所以在 CI 里通常要先把 keystore 以 Secret 存成 base64、在流水线里解码落盘。更省事的做法是本地生成 keystore 后用路线 B 自己签。生成 keystore：

```bash
keytool -genkeypair -v -keystore feishu.jks -alias feishu \
  -keyalg RSA -keysize 2048 -validity 10000
```

---

### 路线 B：本地命令行构建

**B-1. 装 JDK 17**

- Windows：`winget install EclipseAdoptium.Temurin.17.JDK`，或用 Android Studio 自带的 JBR
- macOS：`brew install --cask temurin@17`
- 验证：`java -version` 应显示 `17.x`

**B-2. 装 Android SDK**

最省事是装 [Android Studio](https://developer.android.com/studio)，它会顺带把 SDK 装好。只想要命令行版：

```bash
# 下载 commandline-tools 后
sdkmanager "platform-tools" "platforms;android-37" "build-tools;36.0.0"
```

然后设环境变量（Windows 写进系统环境变量 / macOS 加进 `~/.zshrc`）：

```bash
export ANDROID_HOME=$HOME/Android/Sdk     # Windows: %LOCALAPPDATA%\Android\Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

**B-3. 指定 JDK 与 SDK 路径**

在工程根目录建 `local.properties`（这个文件不要提交到 git）：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

macOS / Linux 写成 `sdk.dir=/Users/你的用户名/Library/Android/sdk`（注意转义与斜杠方向）。

JDK 若不在默认位置，同样在 `local.properties` 或环境变量里指：

```properties
org.gradle.java.home=C\:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot
```

**B-4. 编译**

```bash
# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew

# Debug 包（可直接装，最省事）
./gradlew assembleDebug

# Release 包（体积小、要签名）
./gradlew assembleRelease -Pmaa.abi=arm64-v8a
```

也可以直接用仓库自带的一键脚本（会先做环境自检与字符串校验）：

```bash
./build_apk.sh                # 编 debug
./build_apk.sh release        # 编 release
./build_apk.sh release all    # release + 全 ABI
```

产物位置：

| 构建 | 路径 |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release-*.apk` |

`-Pmaa.abi=` 控制 ABI：`all` / `arm64-v8a` / `x86_64`。给真机装用 `arm64-v8a` 即可；不指定时 debug 默认只出 arm64-v8a。

**B-5. 装到手机**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或把 apk 拷到手机直接点安装（需在系统设置里允许「安装未知来源应用」）。

---

### 常见报错

| 报错 | 原因与解法 |
| --- | --- |
| `Unsupported class file major version` / `Android Gradle plugin requires Java 17` | 用的是 JDK 8/11。装 JDK 17 并在 `local.properties` 指 `org.gradle.java.home` |
| `SDK location not found` | 缺 `local.properties` 或 `sdk.dir` 写错；路径里的 `\` 要转义成 `\\` |
| `Failed to install the following SDK components: platforms;android-37` | SDK 里没装对应 platform，跑 `sdkmanager "platforms;android-37"`；或在 Android Studio 里让它自动补 |
| `i18n verify failed: key mismatch` | `values` 与 `values-en` 的字符串 key 集合必须完全一致。改完跑 `python scripts/check_strings.py app/src/main` 定位 |
| `Cannot find symbol R.string.xxx` | 引用了未定义的字符串。跑 `python scripts/check_strings.py app/src/main` 会列出缺失 key |
| 首次构建卡在 `Downloading gradle-9.4.1-bin.zip` | 正常，Gradle 发行包约 200MB，耐心等或用代理 |

---

### 编译前自检（可选但推荐）

改过工程后先跑一遍，能提前拦住大部分构建失败：

```bash
python scripts/check_strings.py app/src/main                                 # 字符串资源完整性 + i18n 一致性
python scripts/check_deps.py app/src/main/java/com/aliothmoon/maameow        # 工程内 import 可达性
python scripts/prune_stale_tests.py                                          # 失效测试文件
```


---

## 安装与首次配置

1. 安装 APK。
2. **授予无障碍权限** —— 系统设置 → 无障碍 → 已安装的服务 → 开启「飞书打卡助手」。
   - 主服务 `CheckInAccessibilityService`：读控件树 + 执行点击，**打卡必需**。
   - 辅助服务 `AccessibilityHelperService`：仅订阅音量键（无内容读取权限），用于双击音量下键唤起悬浮面板。
3. **授予精确闹钟权限** —— 系统设置 → 应用 → 特殊权限 → 闹钟和提醒。Android 12+ 必需，否则定时不准。
4. **关闭电池优化** —— 否则系统会在后台冻结进程，定时任务不触发。
5. **开启自启动** —— 国产 ROM（MIUI / ColorOS / OriginOS 等）需手动允许，App 内会给出跳转引导。
6. 需要提权（Shizuku / Root）才能使用**自动解锁**；没有提权只能打卡不能自动解锁，需要手动解锁后等打卡流程启动。

### 配置打卡方案

「设置」页可配置：

- 解锁方式（滑动 / PIN / 手势）与自测
- 手势录制（录音式录制解锁手势，供回放）
- 定时策略（「定时任务」页签）：固定时间 / 周期执行，支持多方案
- 通知方式（Webhook / 邮件等）
- 导入 / 导出配置

内置了飞书考勤打卡方案（`BuiltInProfiles.FEISHU`）。若命中失败，用**控件探针**导出真实控件树，据此调整 `NodeSelector` 的匹配文字即可。

---

## 工程结构

```
FeishuCheckIn/
├── app/src/main/java/com/aliothmoon/maameow/
│   ├── constant/          # 包名、常量（FEISHU_PACKAGE_NAME 等）
│   ├── data/
│   │   ├── checkin/       # 打卡方案仓库、背景图存储
│   │   ├── preferences/   # 设置、配置备份
│   │   └── model/         # 数据模型
│   ├── domain/
│   │   ├── checkin/       # CheckInEngine / BuiltInProfiles / 图像匹配
│   │   └── launch/        # LaunchPipeline / CheckInRunner（调度执行内核）
│   ├── schedule/          # 定时任务：闹钟、策略、接收器、UI
│   ├── service/           # CheckInAccessibilityService / AccessibilityHelperService
│   ├── overlay/           # 悬浮球控制面板
│   ├── presentation/      # Compose UI（首页 / 定时 / 探针 / 设置）
│   ├── manager/           # 权限、Shizuku 安装
│   ├── remote/            # 提权进程（解锁、触控注入）
│   └── maa/               # 触控注入核心（InputControlUtils / TouchPointerSequence）
├── build-logic/           # i18n 校验插件
├── annotation-api/        # @PrefSchema / @PrefKey 注解
├── ksp-processor/         # KSP 代码生成
├── hidden-api/            # 隐藏 API 存根
└── scripts/               # 静态自检工具
```

---

## 静态自检工具

改工程后建议先跑这两个脚本（CI 也会跑）：

```bash
# 校验所有 R.string.* / @string/* 引用都在 strings.xml 中有定义，
# 并检查 values 与 values-en 的 key 集合是否一致（不一致会导致构建失败）
python scripts/check_strings.py app/src/main

# 校验所有 com.aliothmoon.maameow.* 的 import 都指向真实存在的符号
python scripts/check_deps.py app/src/main/java/com/aliothmoon/maameow

# 找出引用了已删除符号的测试文件（加 --delete 执行删除）
python scripts/prune_stale_tests.py
```

---

## 相对原 MAA-Meow 的改动

**保留**：定时调度内核、权限层、唤醒解锁（Shizuku/Root 提权 + 手势录制回放）、触控注入、通知体系、主题与外观、日志导出。

**移除**：MaaCore 与全部原生代码、虚拟屏、游戏资源包、任务链与干员识别、成就系统、首启引导、公告、画中画、企划/抄作业、企鹅物流与一图流上报、Mirror酱更新渠道。

---

## 授权

沿用上游授权：见 [LICENSE](LICENSE) 与 [LICENSE-Apache-2.0](LICENSE-Apache-2.0)。
