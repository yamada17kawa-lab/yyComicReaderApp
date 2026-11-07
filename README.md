# 📱 YY漫画阅读器 安卓版 (yyComicReader Android)

<p align="center">
  <img src="Assets/app-icon.png" alt="yyComicReader Android Logo" width="140" height="140" style="border-radius: 28px; box-shadow: 0 8px 24px rgba(0,0,0,0.35);" />
</p>

<p align="center">
  <b>基于 Google Material Design 3 (M3) 规范打造的高性能本地极速漫画阅读器 & 局域网互通终端</b><br>
  <sub>基于 Java 17 + Android SDK 34 构建，支持 Studio 级深色沉浸美学、毫秒级瀑布流垂直长卷阅读、局域网免下载零占用在线书架、异步队列批量安全删除与电脑端数据实时联动。</sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_7.0+-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Design-Material_Design_3-4285F4?logo=google&logoColor=white" alt="M3" />
  <img src="https://img.shields.io/badge/Storage-SAF_+_Shizuku-FF6F00?logo=files&logoColor=white" alt="SAF" />
  <img src="https://img.shields.io/badge/LAN-UDP_Radar_+_HTTP-00C853?logo=wifi&logoColor=white" alt="LAN" />
  <a href="https://github.com/yamada17kawa-lab/yyComicReaderApp/releases/latest"><img src="https://img.shields.io/github/v/release/yamada17kawa-lab/yyComicReaderApp?color=orange&label=Release&logo=github" alt="Release" /></a>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License" />
</p>

<p align="center">
  <a href="https://github.com/yamada17kawa-lab/yyComicReaderApp/releases/latest">
    <img src="https://img.shields.io/badge/📦_点击直接下载-Android_APK安装包-success?style=for-the-badge&logo=android" alt="Download APK Release" />
  </a>
  &nbsp;&nbsp;
  <a href="https://github.com/yamada17kawa-lab/yyComicReaderDesktop">
    <img src="https://img.shields.io/badge/💻_配套电脑桌面中枢端-yyComicReaderDesktop-0078D7?style=for-the-badge&logo=windows&logoColor=white" alt="Desktop Companion Repo" />
  </a>
</p>

---

> [!TIP]
> **💡 与电脑桌面端协同，手机无需下载即可畅读电脑海量漫画！**
> 本项目与配套 Windows 桌面中枢端 **[yyComicReaderDesktop (YY漫画阅读器 桌面版)](https://github.com/yamada17kawa-lab/yyComicReaderDesktop)** 深度协同。
> 只要手机与电脑连接同一 Wi-Fi：
> 1. 打开「局域网互传」，自动 UDP 雷达探测发现电脑并建立高速连接；
> 2. 点击「🖥️ 在线书架」，直接在线浏览并畅读电脑上所有漫画，**手机 0 物理磁盘占用**！
> 3. 手机端阅读后，电脑端对应的阅读次数与进度实时同步，双端数据始终统一。

---

## 🌐 双端协同与局域网无线联动架构

```mermaid
flowchart LR
    subgraph Phone ["📱 安卓移动端 (yyComicReaderApp)"]
        direction TB
        AppUI["Material 3 极速暗黑书架"]
        OnlineShelf["🖥️ 在线书架 (0 存储占用)"]
        BatchQueue["异步队列批量删除"]
        TransferClient["双向免流互传客户端"]
        AppRead["沉浸式阅读器"]
    end

    subgraph PC ["💻 Windows 桌面端 (yyComicReaderDesktop)"]
        direction TB
        DesktopStorage["本地大容量磁盘存储 (数百GB漫画)"]
        HttpServer["内嵌流式 HTTP 服务端 (:19890)"]
        UdpRadar["UDP 广播雷达 (自动发现设备)"]
        ReadSync["阅读次数与历史记录数据库"]
    end

    Phone <==>|"1. 局域网毫秒级自动握手"| UdpRadar
    OnlineShelf <--|"2. 封面与图片流式点播"| HttpServer <-- DesktopStorage
    AppRead -->|"3. 在线阅读计数实时回传 (/api/record-read)"| ReadSync
    TransferClient <==>|"4. 高速物理文件双向互传 (智能去重)"| PC
```

---

## ✨ 核心特性

### 1. 🎨 Google Material Design 3 (M3) 顶级质感美学
- 遵循层次化暗黑表面体系：`#121318` 背景底色、`#1A1D26` 表面容器、`#222634` 微光卡片与 `#2A2F42` 微细精致描边。
- 遵循 8dp 间距规范与平滑圆角（`12dp~16dp`），所有组件均具备清晰的 Hover 与 Pressed 触控状态反馈。

### 2. ⚡ 毫秒级瀑布流垂直阅读与双级 LruCache 内存守护
- **自研双阶段解码下采样引擎**：大尺寸超高清图片预先采样解码，从根源杜绝百页长图长卷阅读时的 OOM 崩溃。
- **双级双向内存缓存**：快速滑屏时平滑释放远端视口切片，保证高帧率滑动与秒开翻页。
- **全屏沉浸手势交互**：单击画面沉浸隐退所有顶底工具栏，配备一键丝滑回到顶部按钮。

### 3. ☁️ 电脑端海量漫画免下载在线观看 (手机 0 磁盘占用)
- 与电脑桌面端建立连接后，一键进入「在线书架」。
- 手机端直接从电脑内存流式拉取封面与漫画页面，即点即看，完全不耗费手机宝贵的机身物理存储空间。

### 4. 🔄 双端阅读次数与状态毫秒级实时回传同步
- 手机端在线观看电脑端漫画时，自动向上汇报阅读事件（`/api/record-read`）。
- 电脑端数据库自动累加该漫画阅读次数，电脑桌面端 UI 实时同步刷新，并在手机在线书架卡片上即时呈现「已读 X 次」。

### 5. 🗑️ 异步后台队列批量删除与实时进度反馈卡片
- **独立单线程删除队列 (`deleteExecutor`)**：告别主线程卡顿与 ANR 假死崩溃，即使批量删除几十部包含数千张图片的漫画，界面依然流畅丝滑。
- **UI 即刻响应脱耦**：确认删除瞬间立即剔除书架视图，用户无需对着弹窗等待。
- **顶栏专属实时进度卡片**：动态呈现进度条、百分比（`XX%`）以及当前正在删除的漫画物理文件名称。

### 6. 📡 局域网 Wi-Fi 智能去重双向极速传输
- **跨平台免流传输**：基于 UDP 广播雷达与高并发 HTTP 传输，毫秒级探测并连接电脑端与局域网内其他手机。
- **智能增量比对**：双方互相对比已有漫画清单，智能标记 `✓ 本机已拥有`，自动跳过重复文件，支持断点续传。

### 7. 📚 全能书架与底层物理目录安全重命名
- **物理文件夹与标题深度安全重命名**：一键修改漫画名称，同时同步安全重命名磁盘上的真实文件夹，无缝迁移历史阅读记录、统计与自定义封面。
- **可视化相册级自定义封面**：支持网格预览漫画内部任意页数，一键点选设为封面，或导入手机相册中的外部图片。
- **智能检索与自然排序 (Natural Order)**：支持模糊匹配与拼音检索，提供名称自然排序、导入时间、阅读量等多维度排序。

### 8. 🛡️ 现代 Android 11~15 存储全适配
- 全面适配 Android 11+ / 14+ `MANAGE_EXTERNAL_STORAGE` 权限与 Storage Access Framework (SAF)。
- 内嵌 Shizuku API 桥接，支持无需 Root 穿透访问受限目录。

---

## 📱 交互操作与手势指南

| 场景 | 手势 / 点击操作 | 功能说明 |
| :--- | :--- | :--- |
| **书架海报** | 单击漫画卡片 | 进入沉浸式漫画阅读器 |
| **书架海报** | 长按漫画卡片 | 调出管理菜单（重命名、修改封面、查看详情、彻底删除） |
| **书架海报** | 点击顶栏「多选」或长按 | 启动多选批处理模式，支持全选与一键批量安全删除 |
| **阅读长卷** | 单击屏幕正中 | 调出 / 隐藏顶部导航条与底部翻页控制条 |
| **阅读长卷** | 快速上下滑动 | 连续瀑布流无缝翻页 |
| **阅读长卷** | 双击画面 / 双指捏合 | 局部高清放大与自由缩放查看细节 |
| **阅读长卷** | 点击右下角置顶悬浮按钮 | 瞬间平滑回到长卷漫画顶部第 1 页 |

---

## 🛠️ 技术栈与依赖架构

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **开发语言** | Java 17 / Android SDK 34 (API 34) | 现代强类型 Android 开发环境 |
| **构建系统** | Gradle 8.7 + Android Gradle Plugin 8.3 | 现代化 Gradle 自动化构建管线 |
| **设计系统** | Google Material Design 3 (`com.google.android.material:material:1.11.0`) | 顶尖深色主题质感与交互规范 |
| **图片引擎** | 双阶段下采样解码 + 内存 LruCache | 自研高性能防 OOM 大图加载引擎 |
| **局域网通信** | 原生多线程 Socket / HTTP Server + UDP Radar | 轻量自研局域网设备发现与高吞吐流式服务 |
| **存储适配** | Android SAF (`DocumentFile`) + `dev.rikka.shizuku:api:13.1.5` | 跨版本存储权限与免 Root 受限目录访问 |

---

## 📁 目录结构说明

```text
ComicReaderApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/comic/reader/     # 核心源码包
│   │   │   ├── MainActivity.java      # 主书架交互中枢、批量队列删除与状态管理
│   │   │   ├── ReaderActivity.java    # 沉浸式阅读器与在线流式解码控制
│   │   │   ├── LanTransferManager.java# 局域网传输网络管理（HTTP 服务 + UDP 雷达）
│   │   │   ├── ComicShelfAdapter.java # 书架网格海报适配器（含在线书架模式）
│   │   │   ├── LanComicSelectAdapter.java # 互传选择器适配器（去重状态标记）
│   │   │   ├── ImageLoader.java       # 高性能内存缓存与大图下采样引擎
│   │   │   ├── ShelfStorage.java      # 书架本地持久化与底层物理文件安全操作
│   │   │   └── ...
│   │   ├── res/                       # Material 3 矢量图标、深色主题与布局 XML
│   │   └── AndroidManifest.xml        # 应用清单元数据与权限配置
│   ├── build.gradle                   # App 模块构建脚本
│   └── proguard-rules.pro             # 代码混淆保护规则
├── gradle/wrapper/                    # Gradle Wrapper 运行时
├── build.gradle                       # 工程全局构建脚本
├── settings.gradle                    # 项目结构设置
├── gradlew / gradlew.bat              # Gradle 构建脚本
└── README.md                          # 项目工程说明文档
```

---

## 🚀 编译与本地运行

### 前置要求
1. 安装 [Android Studio](https://developer.android.com/studio) (推荐 Ladybug / Iguana 或更新版本) 或安装 JDK 17+ 与 Android SDK Platform 34。
2. 配置环境变量 `ANDROID_HOME` 指向您的 Android SDK 安装目录。

### 源码编译
```bash
# 1. 克隆本仓库
git clone https://github.com/yamada17kawa-lab/yyComicReaderApp.git
cd yyComicReaderApp

# 2. 命令行编译 Debug APK (Windows)
.\gradlew.bat assembleDebug

# 或 Linux / macOS
chmod +x gradlew
./gradlew assembleDebug

# 3. 编译发布版 Release APK
.\gradlew.bat assembleRelease
```
> 编译完成后的 APK 输出路径为：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 协议开源。欢迎提交 Issue 与 Pull Request 共同交流完善！
