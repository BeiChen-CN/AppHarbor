# AppHarbor

基于 **Kotlin Multiplatform** 的现代化跨平台应用商店，支持 **Android** 与 **Desktop (JVM)**。采用 **miuix** 设计语言，拥有流畅优雅的视觉交互，聚合多家主流应用源。

AppHarbor 原名 AppMarket，采用蓝色港湾 A 与海浪图标。Android 图标支持自适应形状；Android 13+ 可在支持的桌面开启主题图标，随壁纸取色。可查看 [彩色与莫奈图标预览](branding/appharbor/preview.png) 和 [图标资源说明](branding/appharbor/README.md)。应用包名仍为 `com.app.market`，保留现有数据兼容性。

> [!NOTE]
>
> 本仓库 Fork 自上游 [YXBwbWFya2V0/AppMarket](https://github.com/YXBwbWFya2V0/AppMarket)，并在其基础上持续开发：新增 F-Droid 来源（索引缓存更新检查 + 签名门控）、历史版本多源路由、好游快爆来源等。上游 remote 已配置（`upstream`），便于同步上游更新。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.0-blue?logo=jetpackcompose)](https://github.com/JetBrains/compose-multiplatform)
[![miuix](https://img.shields.io/badge/UI-miuix-FF6900.svg)](https://github.com/compose-miuix-ui/miuix)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Desktop-green.svg)](https://github.com/YXBwbWFya2V0/AppMarket)
[![Android Min SDK](<https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen.svg?logo=android>)](https://android.com)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg?logo=openjdk)](https://openjdk.org)

---

> [!IMPORTANT]
>
> ### 免责与分发来源声明
>
> 1. **零中转服务器**：本应用是一个**纯客户端工具**，本项目**不设立、不运营、不持有任何用于存储、托管或分发第三方应用安装包的服务器**。
> 2. **数据源直连**：应用内展示的所有应用信息、元数据、图标截图、评论以及 APK 安装包 / 增量差分补丁，**全部直接源自对应应用源**。
> 3. **原包与安全**：客户端不对提供的任何应用进行二次打包、注入或篡改；增量更新（差分补丁）均在用户本地与已安装的原版 APK 完成合成。
> 4. **版权与合规**：所有应用、图标、商标等的版权均归各自原厂商/开发者所有。用户需自行判断应用的合法性，并遵守应用源的服务条款与版权规范。

---

## 目录

- [AppHarbor](#appharbor)
  - [目录](#目录)
  - [特性概览](#特性概览)
  - [支持的应用源](#支持的应用源)
  - [安装方式与安装器](#安装方式与安装器)
  - [编译与开发](#编译与开发)
    - [环境要求](#环境要求)
    - [常用命令](#常用命令)
      - [Desktop 端](#desktop-端)
      - [Android 端](#android-端)
  - [运行要求](#运行要求)
  - [开源引用](#开源引用)
  - [免责声明](#免责声明)

---

## 特性概览

- **多源聚合与自由定制**：支持独立为「搜索来源」、「更新来源」以及「今日来源」指定不同的商店源，各取所长。
- **纯客户端增量更新**：内置多套差分算法还原引擎，可用时下载补丁包并于本地合成为完整 APK，节省 70%~90% 流量。
- **全场景安装器支持**：支持系统标准 PackageInstaller、Shizuku 免 Root 静默安装、Root 极速静默安装以及第三方安装器交接。
- **纯净体验与结果净化**：
  - **去除推广应用**：自动隐藏搜索结果中的广告推广。
  - **过滤快应用**：一键隐藏免安装快应用结果。
  - **过滤预约应用**：过滤尚未上线的预注册应用。
  - **优化应用名称**：智能裁剪应用名称后携带的营销副标题与推广宣传语。
- **历史版本回退**：接入海量应用历史库，支持按版本追溯并下载历史版本 APK。
- **设备指纹与机型模拟**：内置预设及自定义 `MarketProfile`（机型、Android 版本、SDK、分辨率、区域等），解决特定厂商或生态专属应用不可见问题。
- **系统生态深度优化**：支持小米 HyperOS 超级岛优化、焦点通知优化等。
- **跨平台一致体验**：基于 Compose Multiplatform 打造，同时支持 Android 移动端与桌面端（Windows / macOS / Linux）。

---

## 支持的应用源

| 应用源                     |    标识     | 搜索 / 详情 | 更新检查 | 增量更新 | 历史版本 |  今日精选   | 真实评论 | 区域切换 | 过滤净化 |
| :------------------------- | :---------: | :---------: | :------: | :------: | :------: | :---------: | :------: | :------: | :------: |
| **小米应用商店** (Xiaomi)  |  `xiaomi`   |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (金米奖) |    ✅    |    —     |    ✅    |
| **vivo 应用商店** (vivo)   |   `vivo`    |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (极光奖) |    ❌    |    —     |    ✅    |
| **OPPO 软件商店** (OPPO)   |   `oppo`    |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (至美奖) |    ✅    |    ✅    |    ✅    |
| **TapTap**                 |  `taptap`   |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (推荐流) |    ❌    |    —     |    —     |
| **华为应用市场** (Huawei)  |  `huawei`   |     ✅      |    ✅    |    ❌    |    ❌    |     ❌      |    ❌    |    —     |    ✅    |
| **荣耀应用市场** (Honor)   |   `honor`   |     ✅      |    ✅    |    ✅    |    ❌    |     ❌      |    ❌    |    —     |    ✅    |
| **三星应用商店** (Samsung) |  `samsung`  |     ✅      |    ✅    |    ❌    |    ❌    |     ❌      |    ❌    |    ✅    |    —     |
| **豌豆荚** (Wandoujia)     | `wandoujia` |     ✅      |   ✅\*   |    ❌    |    ✅    |     ❌      |    ❌    |    —     |    —     |
| **好游快爆** (Kuaibao)     | `kuaibao`   |     ✅      |   ✅\*   |    ❌    |    ❌    |     ❌      |    ✅    |    —     |    —     |
| **F-Droid**                |  `fdroid`   |     ✅      |  ✅\*\*  |    ❌    |    ✅    |     ❌      |    ❌    |    —     |    —     |
| **应用宝** (Tencent)      |  `tencent`  |     ✅      |    ✅    |    ❌    |    ❌    |     ❌      |    ❌    |    —     |    —     |
| **酷安** (CoolApk)         |  `coolapk`  |     ✅      |    ✅    |    ❌    |    ❌    |     ❌      |    ❌    |    —     |    —     |

> _\* 注：豌豆荚与好游快爆无原生批量更新元数据协议，更新检查时目前转发到小米源处理。_
>
> _\*\* 注：F-Droid 基于仓库索引（index-v2）在本地比对更新，并做**签名指纹门控**——仅推荐与本机已安装应用签名兼容的更新（F-Droid 默认对 APK 重签名，与其他来源签名不互通）。首次使用更新检查需同步约 20MB 的 gzip 索引并构建本地缓存；搜索结果为官方接口固定返回的前 10 条，无分页。_

> _\*酷安和应用宝均支持搜索、应用详情、APK 下载、更新检查和手动更新，可在「设置」中分别选为搜索来源和更新来源。

---

## 安装方式与安装器

1. **标准安装 (Standard)**：
   - 调用系统原生 `PackageInstaller` 确认界面。
   - 兼容性最佳，无需任何特殊权限，开箱即用。
2. **Shizuku 静默安装 (Shizuku)**：
   - 借助 [Shizuku](https://shizuku.rikka.app/) 服务在免 Root 情况下获取系统级安装权限。
   - 无需手动点击系统安装弹窗，后台静默无感完成更新与安装。
3. **Root 静默安装 (Root)**：
   - 适用于已获取 Root 权限（KernelSU / Magisk / APatch）的设备。
   - 直接通过底层特权指令高速完成静默安装。
4. **第三方包安装器 (Third-party)**：
   - 支持自定义交接给系统内其他第三方安装器或分发应用处理。

---

「设置 → 下载与安装」提供独立的「更新后自动删除安装包」开关，默认关闭。开启后，仅对已安装应用的新版本安装任务生效：确认目标版本成功安装后，删除该任务对应的安装包（包括保存到 Download 的副本），支持标准、Shizuku、Root 和第三方安装器，以及重启后的安装结果恢复。新装、重装、降级和仅下载任务不会触发此项清理；失败或取消也不会触发。该开关与已有「保存到 Download」和第三方安装器「安装完成后删除安装包」设置独立，原有设置仍按原行为工作。

## 编译与开发

### 环境要求

- **JDK 21** 或更高版本
- **Android SDK**（编译 Android 客户端需要，`compileSdk = 37`, `minSdk = 26`）
- **Gradle**（推荐使用项目自带的 Gradle Wrapper）

### 常用命令

#### Desktop 端

```bash
# 快速编译检查桌面端与公共共享模块
./gradlew :app:shared:compileKotlinDesktop

# 本地直接运行桌面端客户端
./gradlew :app:desktop:run

# 打包当前操作系统格式的桌面安装包（dmg / deb / msi）
./gradlew :app:desktop:packageDistributionForCurrentOS
```

#### Android 端

```bash
# 编译 Android 共享模块
./gradlew :app:shared:compileAndroidMain

# 构建 Android Debug APK
./gradlew :app:android:assembleDebug

# 构建 Android Release APK
./gradlew :app:android:assembleRelease
```

---

## 运行要求

| 平台        | 最低要求                                   | 推荐配置                                     |
| :---------- | :----------------------------------------- | :------------------------------------------- |
| **Android** | Android 8.0 (API 26) 以上                  | Android 10+，已授权 Shizuku 或具备 Root 权限 |
| **Windows** | Windows 10 x64 / ARM64                     | 安装 JRE/JDK 21+                             |
| **macOS**   | macOS 12 Monterey 以上                     | Apple Silicon (M系列) 或 Intel x86_64        |
| **Linux**   | 主流现代发行版（Ubuntu 20.04+、Fedora 等） | 带有 X11 或 Wayland 环境                     |

---

## 开源引用

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) - JetBrains 推出的声明式跨平台 UI 框架
- [miuix](https://github.com/compose-miuix-ui/miuix) - 遵循 MIUI / HyperOS 设计风格的 Compose Multiplatform UI 库
- [Ktor](https://github.com/ktorio/ktor) - 现代化异步多平台 HTTP 客户端
- [Koin](https://github.com/InsertKoinIO/koin) - 轻量级依赖注入框架
- [Coil](https://github.com/coil-kt/coil) - Kotlin Multiplatform 异步图片加载库
- [Shizuku](https://github.com/RikkaApps/Shizuku) - Android 系统级 API 免 Root 调用框架
- [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) - Android 非公开 API 限制绕过方案
- [HDiffPatch](https://github.com/sisong/HDiffPatch) - 高性能差分与补丁还原库
- [HyperNotification](https://github.com/xzakota/HyperNotification) - 小米澎湃焦点通知与超级岛适配支持
- [Obtainium](https://github.com/ImranR98/Obtainium) - 酷安与应用宝来源接入参考

---

## 免责声明

1. **用途限制**：本项目仅供技术研究学习及交流使用，严禁用于任何商业用途或非法牟利行为。
2. **商标与版权**：文中提及的 Xiaomi、vivo、OPPO、Honor、Huawei、Samsung、TapTap、豌豆荚、好游快爆、F-Droid 等商标及产品名称，其商标权与著作权均归其合法所有者所有。
