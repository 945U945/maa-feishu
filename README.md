# FeishuCheckIn

飞书自动打卡工具。基于 Android **无障碍服务**（AccessibilityService）在预定时刻自动打开飞书并完成上下班打卡。

> 从零重写的版本。核心思路借鉴 [MAA](https://github.com/MaaAssistantArknights/MaaAssistantArknights) 的「把操作路径抽象成数据」与「结果语义严格分离」，但不搬任何游戏相关代码。

---

## 为什么用无障碍，而不是截图 + 图像识别

飞书对考勤页面设置了 `FLAG_SECURE`，**任何截图都会返回全黑**。
图像识别的路线在这一步就死了。

因此改为读取**控件树**（`AccessibilityNodeInfo`）：
通过文本 / 类名 / 资源 ID 定位控件，再执行点击。
好处是稳定、无分辨率依赖；代价是需要用户手动开启无障碍权限。

---

## 核心设计

### 1. 点击路径写成数据，不是代码

飞书的考勤入口在不同企业、不同版本里位置不同 ——
有的在工作台，有的在「我的」页，有的企业换成自建应用。

把路径抽象成 `CheckInPlan`（若干有序的 `CheckInStep`）后，
适配新布局**只需加一份 JSON，不必改代码重新发版**。

内置三个方案（`builtin_workbench` / `builtin_profile` / `builtin_search`），
自定义方案放在 `<filesDir>/plans/` 下的 json 文件里，同名 ID 时**自定义覆盖内置** ——
给高级用户一个「改内置方案」的口子。

### 2. 三层闹钟恢复

用 `AlarmManager.setAlarmClock` 而非 WorkManager：
后者最小周期 15 分钟且允许延迟，而打卡时间点不能漂。

代价是状态栏会有常驻闹钟图标。为了应对国产 ROM 拦截开机广播，
做了三层幂等恢复：

| 层 | 触发时机 | 作用 |
|---|---|---|
| 1 | `BootReceiver` 监听 5 个广播 | 正常路径 |
| 2 | App 冷启动全量重排 | 兜底被拦截的开机广播 |
| 3 | `MainActivity.onStart` 每次进入都重排 | 用户手动打开最可靠 |

三层都是**幂等**的：先 `cancel` 再 `setAlarmClock`，重复执行无害。

### 3. 「先续排、后执行」

闹钟触发后，先安排下一次闹钟，**再**执行打卡。
这样即使本次执行卡死或崩溃，下次闹钟已经排好，不会连锁丢失。

### 4. 结果语义严格分离

| 结果 | 是否算失败 | 说明 |
|---|---|---|
| `SUCCESS` | 否 | 打卡成功 |
| `ALREADY_DONE` | **否** | 今天已打过，跳过 |
| `SKIPPED_BUSY` | **否** | 已有任务在执行 |
| `ENTRY_NOT_FOUND` | 是 | 找不到打卡入口 |
| `NETWORK_ERROR` | 是 | 网络问题，**可重试** |
| `DEVICE_LOCKED` | 是 | 设备锁屏，**不可重试** |
| `PERMISSION_MISSING` | 是 | 无障碍未开启 |
| `FAILURE` | 是 | 其他，**可重试** |

关键在于 `ALREADY_DONE` 和 `SKIPPED_BUSY` **不能标红**。
如果「已打卡」也被当成失败，用户会习惯性忽略所有红色提示，
真正的「未找到打卡入口」就被淹没了。

重试只针对**可能自愈**的失败（`isRetryable`），不对确定性失败重试。

### 5. 幂等键 = 日期 + 类型

`"${planId}_${kind.name}_${yyyymmdd}"`

防止「同一天被不同触发源各打一次」。这不只是省事 ——
**重复打卡在有些企业会被记为考勤异常**，所以「不重复打」是正确性要求。

### 6. 平台查询以 lambda 注入

核心类（`CheckInEngine` / `NextTriggerCalculator`）**不持有 Context**。
需要平台能力时，通过构造函数注入 `() -> Boolean` 这样的 lambda。

好处有两个：
- 可以在**纯 JVM 环境**写单元测试，不需要 Robolectric
- 避免「用反射从别的对象掏 Context」这种脆弱写法 ——
  反射在 R8 混淆后必然失效，那是上一版的闪退根因

### 7. 启动顺序是经过设计的

```
1. BootTrace.install(this)     ← 零依赖，必须第一句
2. startKoin { ... }            ← 依赖注入
3. CrashHandler.install(this)   ← 崩溃落盘
4. Timber.plant(FileLogTree)    ← 文件日志
5. 冷启动恢复调度                ← 幂等重排闹钟
```

`BootTrace` 刻意做到零依赖（只用 `android.util.Log` 和 `java.io`），
因此可以、也必须排在最前面。

**上一版的教训**：崩溃处理器装在依赖注入之后，
于是注入阶段抛异常时处理器还没挂上，堆栈既不落盘也进不了 logcat，
现象是「点图标闪一下就走，且查不到任何原因」。

---

## 技术栈

| 项 | 版本 |
|---|---|
| AGP | 9.2.1 |
| Kotlin | 2.4.10 |
| Gradle | 9.4.1 |
| compileSdk / minSdk / targetSdk | 37 / 28 / 36 |
| JDK | 17 |
| Compose BOM | 2026.05.01 |
| Koin | 4.2.2 |
| DataStore | 1.2.1 |

**注意**：AGP 9.0+ 内置 Kotlin 支持，
`org.jetbrains.kotlin.android` 插件**不能再应用**，否则构建直接失败。

---

## 工程结构

垂直切片，而不是按「层」分：

```
com/feishu/checkin/
├── accessibility/          无障碍服务 + 控件查找
├── checkin/                打卡功能（自成一体的切片）
│   ├── model/              数据模型（纯数据，可序列化）
│   ├── data/               持久化与内置方案
│   ├── engine/             执行引擎
│   ├── alarm/              闹钟调度
│   ├── receiver/           广播接收（开机 / 闹钟）
│   └── service/            前台服务
├── core/
│   ├── time/               触发时刻计算（纯函数，重点测试对象）
│   ├── device/             设备状态、唤醒、权限体检
│   ├── log/                日志与崩溃落盘
│   └── di/                 依赖注入
└── ui/                     三个页面（首页 / 历史 / 设置）
```

打卡需要的所有依赖都在 `checkinModule` 里 ——
上一版闪退的原因之一就是「一个功能的依赖散在三个模块里，漏注册看不出来」。

---

## 构建与验证

```bash
# 编译
./gradlew :app:compileDebugKotlin --no-daemon

# 单元测试（31 个用例）
./gradlew :app:testDebugUnitTest --no-daemon

# Debug 包
./gradlew :app:assembleDebug --no-daemon

# Release 包（R8 + 签名）
./gradlew :app:assembleRelease --no-daemon
```

### 静态自检

几个脚本能在几秒内捕获「编译通过但运行时炸」的问题，
CI 里按「越快越靠前」的顺序执行：

```bash
python scripts/check_eol.py .              # 关键文件行尾（必须最先，见下）
python scripts/check_koin.py               # 依赖注入完整性
python scripts/check_comment_balance.py app/src   # Kotlin 块注释配对
python scripts/check_strings.py            # 多语言字符串一致性
```

> **行尾检查为什么排第一**：若 `gradlew` 带 CRLF，shebang 会变成
> `#!/usr/bin/env sh\r`，Linux 上找不到该解释器 →
> `exit 127`，且日志里**一行 Gradle 输出都没有**，极易误诊为构建配置问题。
> 用 `check_eol.py` 提前拦住，报错点与根因就在同一处。
>
> 注意：**不要用 `grep -c $'\r'` 判断行尾** —— Git Bash 的 grep 会做
> 文本模式转换，把纯 LF 文件也报成含 CR。必须数字节，脚本里已处理。

### 仓库维护脚本

```bash
# 完整镜像同步（会删除远端多余文件！）
python scripts/sync_to_github.py --dry-run
python scripts/sync_to_github.py

# 配置 CI 签名 secrets
python scripts/setup_ci_secrets.py

# 查看 CI 状态
python scripts/ci_status.py
python scripts/ci_status.py --run <run_id>
python scripts/ci_status.py --log <run_id>
```

> ⚠️ **务必用 `sync_to_github.py` 而非 `push_to_github.py`。**
> 后者只有「新增 + 更新」语义，没有删除。
> 在推倒重写的场景下会把旧代码留在远端，CI 就会 checkout 到
> 一棵新旧混合的树，报出大量指向错误方向的错误。

---

## CI 流水线

| 流水线 | 触发 | 产物 |
|---|---|---|
| `build-dev.yml` | push 到 main / PR / 手动 | Debug APK（artifact，保留 14 天） |
| `build-release.yml` | 打 tag `v*` / 手动 | 签名 Release APK → **挂到 GitHub Release 页** |

### 已跑通的状态

- Dev Build **#90 ✓**（四个静态检查 + 单测 + 编译 + APK 结构校验全过）
- Release Build **#8 ✓**（手动触发）/ **#9 ✓**（tag `v1.0.0` 触发，含 `Create Release`）
- 挂包：`https://github.com/945U945/maa-feishu/releases/tag/v1.0.0`
  （`app-release.apk`，6.78 MB，v2 签名）

### 发版流程

```bash
# 1. 推送代码
python scripts/sync_to_github.py

# 2. 把 tag 指向当前 HEAD（若 tag 已存在需 PATCH 强制更新）
#    POST  /git/refs        {"ref":"refs/tags/v1.0.0","sha":"<head>"}
#    PATCH /git/refs/tags/v1.0.0  {"sha":"<head>","force":true}

# 3. tag push 自动触发 Release 构建，跑完自动创建 Release 页并挂包
```

### 两个必须知道的 CI 环境约定

1. **SDK platform 目录名是 `android-37.0`，不是 `android-37`。**
   从 API 36 起 Google 给 platform 目录加了小版本号。
   判断「是否已安装」必须用通配 `android-37*`，
   写死 `android-37` 会导致「装好了却报安装失败」——
   报错信息与真实状态完全相反。
   （`compileSdk = 37` 不受影响，AGP 内部会映射到 `android-37.0`）

2. **不用 `android-actions/setup-android`。**
   它内部执行 `sdkmanager "tools"`，而 `tools` 包已从 Google 仓库移除，
   job 会在这一步直接挂掉。`ubuntu-latest` runner 自带 Android SDK，
   手工配置环境变量即可。

---

## 签名

Release 包用 `keystore/feishu-checkin.jks` 签名。
密钥库**不入仓库**（`.gitignore` 已排除），CI 通过 secrets 还原：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | keystore 的 base64 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | `feishu-checkin` |
| `KEY_PASSWORD` | 密钥口令 |
| `KEYSTORE_PATH` | `release.jks`（**相对仓库根**） |

路径基准是三方对齐的：代码用 `rootProject.file()` 解析，
CI 把 keystore 还原到仓库根，secret 填不带目录的文件名。

### 校验签名

**APK 签名块不在 `META-INF/` 里。** v2/v3 签名位于 ZIP 中央目录之后的
APK Signing Block，特征是魔数 `APK Sig Block 42`。
只扫 `META-INF/` 会把已签名的包误判成未签名。

```bash
grep -a -q "APK Sig Block 42" app-release.apk && echo signed || echo unsigned
apksigner verify --verbose --print-certs app-release.apk
```

---

## 已知限制

- **锁屏状态下无法打卡**。有密码的设备系统必须要求用户交互，
  第三方应用无解。引擎会如实返回 `DEVICE_LOCKED`，不会假装成功。
- 需要用户手动开启无障碍权限，并建议关闭电池优化。
- 飞书版本更新可能导致内置方案的控件文案失效，
  此时可用「控件探针」导出当前页面结构，据此编写自定义方案 JSON。

---

## 开发时踩过的坑

这些坑都写进了代码注释，也浓缩在这里，避免重复踩：

1. **Kotlin 块注释会嵌套**。注释里写 `plans/*.json` 会让 `/*` 开启嵌套注释，
   整个文件解析失败，然后**所有引用它的文件都报 `Unresolved reference`** ——
   1 个语法错误表现为 30 个分散的错误。
   排查用 `scripts/check_comment_balance.py`。
2. **`@Volatile` 不能标注局部变量**（它作用于字段）。
   被闭包捕获的 var 本就没有 volatile 语义。
3. **`koin.get(clazz = KClass<*>)`** 需要显式类型实参，
   星投影推断不出 `T`。
4. **依赖图自检不能放在 `startKoin { }` 块内** ——
   块内实例未装配完，会把正常注册误判为缺失。
5. **Compose 里必须用 `koinViewModel()`**，
   `androidx.lifecycle...viewModel()` 不认识 Koin，运行时会崩而编译期不报错。
6. **Splash 主题引用的图标类型要对**：
   前景图在 `drawable/`，写成 `@mipmap/` 会 AAPT2 报资源找不到。
7. **`android-actions/setup-android` 在新 runner 上会失败** ——
   它内部执行 `sdkmanager "tools"`，而 `tools` 包已从仓库移除。
   已改为手工配置 SDK。

---

## 许可

个人自用工具。
