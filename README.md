# 📱 HomeOS 安卓伴侣端

> 工业级安卓伴侣与壁挂式大屏 Kiosk 控制端 / Industrial-grade Android Companion & Wall-mounted Kiosk Display Wrapper.

`HomeOS-Android` 是专为 **HomeOS 智能家居管理终端** 量身打造的高性能原生 Kotlin 容器。它不仅是一个高度加速的 Web 容器，更是一个集成了局域网智能漫游、多通道云推送（FCM / 华为 HMS）、硬件控制和断网自愈能力的工业级控制中心。

---

## 🌟 核心特性 / Core Features

### 1. 沉浸式 Kiosk 锁屏控制 / Immersive Kiosk Mode
* **全屏硬件锁定**：自动隐藏系统状态栏与虚拟导航键，防止用户意外退出。
* **物理防误触机制**：支持阻断物理返回键（基于 `OnBackPressedDispatcher` 实现），通过**三指三连击**或**右上角五连击**调用管理员设置面板。
* **硬件唤醒 (WakeLock)**：接收到门铃、漏水或火灾等高危报警时，自动唤醒并点亮屏幕，直接显示对应摄像头画面。

### 1.1 分辨率自适应与输入体验 / Adaptive Resolution & Input UX
* **任意分辨率等比全屏**：默认以**设备真实分辨率**作为设计画布，配合 `useWideViewPort + loadWithOverviewMode` 由渲染引擎等比铺满任意屏幕（不再固定适配单一机型），高级设置可手动覆盖设计画布尺寸。
* **软键盘智能避让**：沉浸式全屏下监听 IME 窗口插入（`WindowInsets.ime()`），弹出键盘时自动上移设置面板，杜绝账号/密码输入框被键盘遮挡。
* **渲染崩溃自愈**：监听 WebView 渲染进程终止（`onRenderProcessGone`），自动重建恢复；并内置**限频与退避**保护，避免页面持续 OOM 导致的紧密重建死循环。

### 2. 双通道智能漫游与自愈 / Smart Roaming & Auto-Healing
* **智能漫游探测**：启动时自动探测局域网网关（LAN URL），若可用则优先走局域网；若离家则无感切换至外网（WAN URL）。
* **断网自愈界面**：当网络完全中断时，自动显示高颜值的毛玻璃霓虹断网报警屏，并在后台保持高频重试，网络恢复后无感刷新还原。

### 3. 多通道实时报警监听器 / Real-time Alarm Service
* **前台服务守护进程**：在后台启动符合 Android 14 最新规范的 `remoteMessaging` 前台服务，通过轮询 `/api/v1/notifications` 与 HomeOS 后端保持告警同步。
* **双通道云推送**：集成 **Google FCM** 与 **华为 HMS Push Kit**（完美兼容 HarmonyOS 3/4 及国内无 GMS 环境），支持 App 被系统杀掉后的强行唤醒推送。
* **跨通道告警去重**：FCM/HMS 推送与后台轮询可能命中同一条告警，进程级去重窗口确保同一告警**不会重复发声/震动/唤醒屏幕**。
* **前后台分级轮询**：前台（网页自身已实时取数）放慢轮询、后台以推送为主轮询兜底，降低无谓请求与耗电。

### 4. 零延迟动态多语言切换 / Dynamic i18n Engine
* **即时无闪烁刷新**：在设置弹窗顶部集成紧凑的 `简/EN` 双语胶囊按钮。点击时通过内存直译机制更新，**彻底告别 Activity 销毁重建导致的屏幕闪烁**。
* **表单零数据丢失**：在切换语言时，用户当前正在填写的 IP 地址、用户名和密码等配置完美保留。

### 5. 安全隐私保护 / Security & Privacy
* **隐私协议屏障**：首次启动时强制显示隐私协议，未同意前不会初始化任何第三方 SDK 或发起网络请求。
* **硬件指纹认证**：使用 `ANDROID_ID` 的 SHA-256 哈希值作为设备唯一标识（HWID），用于 Pro 版授权验证。
* **凭据加密存储**：账号、密码、认证/CSRF 令牌通过 **`EncryptedSharedPreferences`（Android Keystore 加密）** 落盘（`SecureStore`），并自动从旧明文存储迁移、清除明文残留；Keystore 异常时具备删除重建容错。
* **会话保持**：冷启动用加密保存的凭据**静默重新登录一次**换取有效令牌，并持久化认证 Cookie，避免每次重进 App 都要手动输入账号密码。
* **WebStorage 安全清理**：退出演示模式后彻底清空 LocalStorage/SessionStorage，避免使用危险的磁盘物理删除操作导致 Chromium 崩溃。

### 6. 窗口生命周期保护 / Window Lifecycle Safety
* **BadTokenException 防护**：所有 `recreate()` 调用前均增加 `!isFinishing && !isDestroyed` 双重状态保护，规避在 Activity 销毁后操作失效的 Window Token 导致的闪退。
* **安全时序控制**：先隐藏设置面板，再在安全回调中延迟触发重构重建，确保 UI 操作与 Activity 生命周期同步。

### 7. 前台服务优化 / Foreground Service Optimization
* **智能服务管理**：退出演示模式或擦除配置后，主动停止后台 WebSocket 长连接前台服务，避免无意义空跑。
* **开机自启限制**：仅当设备已配置（`is_configured = true`）时才启动前台服务，节省未配置设备的系统资源。

### 8. 网络与渲染性能 / Network & Rendering Performance
* **统一 OkHttp 客户端**：登录、局域网探测、通知轮询统一走共享 `OkHttpClient`（连接池/线程池复用），按需派生超时。
* **路由解析缓存**：局域网/外网路由探测结果带缓存（地址变更自动失效、断网恢复强制重探），避免每次加载都做一次探测。
* **单次静默登录**：本进程内仅在首次成功登录前重登录一次，网络抖动恢复时直接复用令牌，减少重复登录与服务端压力；登录成功后同步刷新后台服务令牌。
* **轻量化渲染**：去除整页强制硬件层与离屏预栅格，改由渲染引擎做等比缩放，降低 GPU 显存占用、提升清晰度。
* **R8 代码压缩**：Release 开启 R8 + 资源收缩（已配齐 JS 桥、Firebase/HMS、OkHttp、Tink 的 keep 规则），CI 归档 `mapping.txt` 以便崩溃栈还原。

---

## 📂 项目结构 / Directory Structure

```
HomeOSAPP/
├── settings.gradle.kts          # Gradle 多仓库配置（整合 GMS + 华为 Maven）
├── build.gradle.kts             # 根目录编译插件与 Kotlin 版本管理
├── gradle.properties            # JVM 内存调优与 AndroidX 兼容开关
└── app/
    ├── build.gradle.kts         # App 依赖配置
    ├── proguard-rules.pro       # 代码混淆规则
    └── src/main/
        ├── AndroidManifest.xml  # 系统权限、前台服务及开机自启广播声明
        ├── res/                 # 资源文件（多语言字符串、布局、主题）
        │   ├── values/          # 默认（中文）字符串、主题、颜色
        │   ├── values-en/       # 英文字符串
        │   ├── values-zh/       # 中文字符串
        │   ├── layout/          # activity_main / activity_offline 布局
        │   ├── drawable/        # 图标与毛玻璃面板背景
        │   └── xml/             # 网络安全配置、FileProvider 路径
        └── java/com/homeos/app/
            ├── MainActivity.kt              # 主界面、生命周期协调、手势/返回/IME 处理
            ├── WebViewController.kt         # WebView 初始化、viewport 注入、渲染崩溃自愈
            ├── ViewportInjector.kt          # 固定宽度 viewport 注入脚本（防抖守护）
            ├── NetworkCoordinator.kt        # 路由加载、登录注入、离线恢复与超时管理
            ├── NetworkRoamingManager.kt     # 局域网/外网双通道漫游引擎（带路由缓存）
            ├── SettingsController.kt        # 设置面板 UI、语言切换与配置保存
            ├── HomeOsAuth.kt                # 登录、Cookie 鉴权与令牌读写
            ├── SecureStore.kt               # 凭据加密存储（EncryptedSharedPreferences）+ 迁移
            ├── HttpClientProvider.kt        # 共享 OkHttpClient（连接池复用）
            ├── NativeBridge.kt              # 注入 window.Native 的 JS 桥
            ├── NativeBridgeHost.kt          # JS 桥所需 Activity 状态抽象
            ├── SocketService.kt             # 后台通知轮询守护服务
            ├── NotificationOrchestrator.kt  # 报警分发、显示唤醒、静音绕过与去重
            ├── FcmService.kt                # Google FCM 推送服务
            ├── HmsService.kt                # 华为 HMS 推送服务
            └── BootReceiver.kt              # 开机自启广播接收器
```

---

## 🛠️ 技术栈 / Technology Stack

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9.22 |
| 框架 | AndroidX | 1.12.0 |
| 构建工具 | Gradle | 8.9 |
| 编译目标 | Android SDK | 34 |
| 最低版本 | Android | 8.0 (API 26) |
| 网络 | OkHttp | 4.12.0 |
| JSON | org.json（系统内置） | — |
| 凭据加密 | AndroidX Security Crypto | 1.1.0-alpha06 |
| 推送 | Firebase FCM | 23.4.1 |
| 推送 | Huawei HMS Push | 6.12.0.300 |
| WebView | AndroidX WebKit | 1.10.0 |
| 代码压缩 | R8（Release 启用） | — |

---

## 🔧 编译与部署 / Development & Build Guide

### 1. 环境要求
- JDK 17（不支持 JDK 25+）
- Android SDK 34
- Gradle 8.9

### 2. Windows 本地编译

```powershell
# 安装 JDK 17
winget install EclipseAdoptium.Temurin.17.JDK

# 进入项目目录
cd HomeOSAPP

# 编译 Debug 版本
.\gradlew clean assembleDebug

# 编译 Release 版本
.\gradlew clean assembleRelease
```

### 3. 本地调试（Android Studio）
1. 启动 **Android Studio**，选择 **Open** 导入 `HomeOSAPP` 文件夹
2. 同步 Gradle 完成后即可直接连接真机/模拟器一键运行。

### 4. 输出路径
```
app/build/outputs/apk/debug/HomeOSAPP+<version>.apk     # Debug 包
app/build/outputs/apk/release/HomeOSAPP+<version>.apk    # Release 包（已 R8 压缩）
app/build/outputs/mapping/release/mapping.txt           # R8 反混淆映射（崩溃栈还原用）
```
> Release 已启用 R8 代码压缩与资源收缩；上线前请用 Release 包做一次真机冒烟（JS 桥调用、文件上传、推送告警、局域网/外网路由切换）。

---

## 🔒 跨端桥接协议 / JS Bridge Specification

伴侣端向 Web 前端自动注入 `window.Native` 对象，支持以下原生交互：

| 方法 | 描述 | 返回值 |
|------|------|--------|
| `getHardwareFingerprint()` | 获取由 `ANDROID_ID` 进行 SHA-256 加密后的硬件唯一标识（HWID） | `string` |
| `getPushToken()` | 获取当前活跃的 FCM / HMS 推送 Token | `string` |
| `getDeviceInfo()` | 获取设备信息（型号、厂商、系统版本、屏幕像素、设计基准分辨率、等比缩放因子等） | `JSON string` |
| `getAppVersion()` | 获取应用版本号 | `string` |
| `getAppMode()` | 获取当前运行模式（kiosk/companion） | `string` |
| `playAlertSound(severity)` | 根据警报级别播放对应声调 | `void` |
| `acknowledgeAlert(alertId)` | 取消指定的系统通知 | `void` |

`getDeviceInfo()` 额外返回字段：`screenWidthPx`、`screenHeightPx`、`density`、`densityDpi`、`designWidth`、`designHeight`（设计画布，默认取**设备真实分辨率**，可在高级设置覆盖）、`scaleFactor`（终端等比缩放因子）。

---

## 📋 权限说明 / Permissions

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络访问 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `ACCESS_WIFI_STATE` | WiFi 状态检测 |
| `WAKE_LOCK` | 保持屏幕常亮（Kiosk 模式） |
| `DISABLE_KEYGUARD` | 禁用锁屏 |
| `POST_NOTIFICATIONS` | 发送通知（Android 13+） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `FOREGROUND_SERVICE` | 前台服务 |
| `FOREGROUND_SERVICE_REMOTE_MESSAGING` | 远程消息前台服务（Android 14+） |
| `REQUEST_INSTALL_PACKAGES` | 安装未知来源应用（OTA 更新） |

---

## 📄 协议 / License

MIT License

---

## 📞 联系方式 / Contact

如有问题或建议，请提交 Issue 或联系开发团队。
