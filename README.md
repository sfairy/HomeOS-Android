# 📱 HomeOS 安卓伴侣端

> 工业级安卓伴侣与壁挂式大屏 Kiosk 控制端 / Industrial-grade Android Companion & Wall-mounted Kiosk Display Wrapper.

[![平台](https://img.shields.io/badge/Platform-Android%208.0+-00C853?style=for-the-badge&logo=android)](https://developer.android.com)
[![构建](https://img.shields.io/badge/Build-Gradle%20Kotlin%20DSL-6200EE?style=for-the-badge&logo=gradle)](https://gradle.org)
[![版本](https://img.shields.io/badge/Version-2026.06.15.07-FF1744?style=for-the-badge)](https://github.com/pcskycn/HomeOS-Android)

`HomeOS-Android` 是专为 **HomeOS 智能家居管理终端** 量身打造的高性能原生 Kotlin 容器。它不仅是一个高度加速的 Web 容器，更是一个集成了局域网智能漫游、多通道云推送（FCM / 华为 HMS）、硬件控制和断网自愈能力的工业级控制中心。

---

## 🌟 核心特性 / Core Features

### 1. 沉浸式 Kiosk 锁屏控制 / Immersive Kiosk Mode
* **全屏硬件锁定**：自动隐藏系统状态栏与虚拟导航键，防止用户意外退出。
* **物理防误触机制**：支持阻断物理返回键，通过**三指三连击**或**右上角五连击**调用管理员设置面板。
* **硬件唤醒 (WakeLock)**：接收到门铃、漏水或火灾等高危报警时，自动唤醒并点亮屏幕，直接显示对应摄像头画面。

### 2. 双通道智能漫游与自愈 / Smart Roaming & Auto-Healing
* **智能漫游探测**：启动时自动探测局域网网关（LAN URL），若可用则优先走局域网；若离家则无感切换至外网（WAN URL）。
* **断网自愈界面**：当网络完全中断时，自动显示高颜值的毛玻璃霓虹断网报警屏，并在后台保持高频重试，网络恢复后无感刷新还原。

### 3. 多通道实时报警监听器 / Real-time Alarm Service
* **前台服务守护进程**：在后台启动符合 Android 14 最新规范的 `remoteMessaging` 前台服务，通过轮询 `/api/v1/notifications` 与 HomeOS 后端保持告警同步。
* **双通道云推送**：集成 **Google FCM** 与 **华为 HMS Push Kit**（完美兼容 HarmonyOS 3/4 及国内无 GMS 环境），支持 App 被系统杀掉后的强行唤醒推送。

### 4. 零延迟动态多语言切换 / Dynamic i18n Engine
* **即时无闪烁刷新**：在设置弹窗顶部集成紧凑的 `简/繁/EN` 三语胶囊按钮。点击时通过内存直译机制更新，**彻底告别 Activity 销毁重建导致的屏幕闪烁**。
* **表单零数据丢失**：在切换语言时，用户当前正在填写的 IP 地址、用户名和密码等配置完美保留。

### 5. 安全隐私保护 / Security & Privacy
* **隐私协议屏障**：首次启动时强制显示隐私协议，未同意前不会初始化任何第三方 SDK 或发起网络请求。
* **硬件指纹认证**：使用 `ANDROID_ID` 的 SHA-256 哈希值作为设备唯一标识（HWID），用于 Pro 版授权验证。
* **本地加密存储**：所有服务器凭据通过 SharedPreferences 加密存储，WebView 会话受系统沙盒保护。

### 6. OTA 自动更新 / Over-the-Air Updates
* **双通道下载**：支持 GitHub Releases 自动检测更新，直连失败时自动切换加速通道。
* **安全升级**：提供优雅的升级对话框，显示版本对比和更新日志。

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
        │   ├── values/          # 中文默认字符串
        │   ├── values-en/       # 英文字符串
        │   ├── values-zh/       # 中文字符串
        │   └── values-zh-rTW/   # 繁体中文字符串
        └── java/com/homeos/app/
            ├── MainActivity.kt          # 主界面、WebView 容器、手势拦截
            ├── HomeOSAuth.kt            # Cookie 鉴权与登录辅助
            ├── SocketService.kt     # 后台通知轮询守护服务
            ├── FcmService.kt        # Google FCM 推送服务
            ├── HmsService.kt        # 华为 HMS 推送服务
            ├── NetworkRoamingManager.kt # 局域网/外网双通道漫游引擎
            ├── NotificationOrchestrator.kt # 报警分发、显示唤醒与静音绕过
            ├── BootReceiver.kt          # 开机自启广播接收器
            └── update/                  # OTA 更新模块
                ├── UpdateService.kt     # GitHub 更新检测服务
                ├── UpdateDialog.kt      # 升级对话框 UI
                ├── ApkDownloader.kt     # 双通道 APK 下载器
                └── InstallUtil.kt       # APK 安装工具
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
| JSON | Gson | 2.10.1 |
| 推送 | Firebase FCM | 23.4.1 |
| 推送 | Huawei HMS Push | 6.12.0.300 |
| WebView | AndroidX WebKit | 1.10.0 |

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
cd e:\源码实例\HomeOS\HomeOSAPP

# 编译 Debug 版本
.\gradlew clean assembleDebug

# 编译 Release 版本
.\gradlew clean assembleRelease
```

### 3. 本地调试（Android Studio）
1. 启动 **Android Studio**，选择 **Open** 导入 `HomeOSAPP` 文件夹
2. 同步 Gradle 完成后即可直接连接真机/模拟器一键运行。

### 4. APK 输出路径
```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

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
| `startUpgrade(downloadUrl)` | 触发 OTA 升级流程 | `void` |

`getDeviceInfo()` 额外返回字段：`screenWidthPx`、`screenHeightPx`、`density`、`densityDpi`、`designWidth`（默认 3200）、`designHeight`（默认 2136）、`scaleFactor`（终端等比缩放因子）。

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
