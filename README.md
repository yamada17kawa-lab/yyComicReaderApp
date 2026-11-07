# YY漫画阅读器 安卓版 (ComicStream Android)

> 📱 **基于 Google Material Design 3 (M3) 设计规范打造的现代化本地极速漫画阅读器**  
> 拥有 Studio 级深色沉浸美学、毫秒级图片流式渲染、局域网 Wi-Fi 漫画双向秒传、免下载零占用在线书架观看以及双端阅读状态实时互通。

---

## 🌟 核心特性

- 🎨 **Google Material Design 3 (M3) 顶级质感美学**
  - 采用层次化暗黑表面体系（`#121318` 底色、`#1A1D26` 表面容器、`#222634` 微光卡片与 `#2A2F42` 微细描边），告别简陋粗糙的传统工具界面。
  - 符合 8dp 间距体系与圆角规范，全组件定义 Hover、Pressed 触控反馈状态。
- ⚡ **毫秒级瀑布流垂直阅读与内存守护**
  - 自研双级双向内存 `LruCache` 与双阶段下采样解码（避免大尺寸 WebP/JPG 图片导致 OOM 崩溃）。
  - 支持单页滑动沉浸模式与连续瀑布流无缝长卷，双击/手势缩放支持，阅读中智能隐藏顶底工具栏，配备一键平滑回到顶部。
- 📡 **局域网 Wi-Fi 免流互传中枢**
  - 内置基于 UDP 广播雷达与 HTTP 高并发的传输服务，同一局域网下毫秒级自动握手连接 Windows 电脑与另一台手机。
  - 智能去重传输：自动对比双方已有漫画，已拥有的内容自动跳过不重复传送，支持断点重试与传输进度实时展示。
- ☁️ **电脑端漫画免下载在线观看 (零磁盘占用)**
  - 与电脑桌面版深度联动，手机端可一键进入电脑在线书架，直接从电脑端流式读取封面与漫画切片并在内存中解码渲染，**手机物理磁盘占用为 0**！
  - **跨端阅读状态实时联动**：在手机端在线观看电脑漫画时，自动向上汇报电脑端，电脑端数据库对应的阅读次数与进度实时更新并同步保存。
- 📚 **全能书架与文件安全管理**
  - **物理文件夹与标题安全重命名**：一键重命名漫画，底层物理目录与封面、历史阅读记录无缝同步。
  - **可视化相册级自定义封面**：支持网格预览漫画内部所有页面切片，点选任意画面即可设为封面，亦可从手机相册导入。
  - **智能模糊匹配与多重排序**：支持跳字模糊搜索、拼音检索，提供名称自然排序（Natural Order）、导入时间、阅读次数最多、最近阅读及页数最多等多重排序。
  - **批量管理彻底清理**：支持多选批量管理，可选同步彻底清理手机存储上的本地物理文件夹。
- 🛡️ **现代 Android 存储机制全适配**
  - 完美适配 Android 11+ / 14+ `MANAGE_EXTERNAL_STORAGE` 与 Storage Access Framework (SAF)。
  - 内嵌 Shizuku API，支持无需 Root 穿透访问受限目录。

---

## 🛠️ 技术架构与依赖

- **运行环境**: Android 7.0 (API 24) 及以上，推荐 Android 12 ~ 15
- **开发语言**: Java 17+ / Gradle 8.7
- **核心组件与依赖**:
  - `androidx.appcompat:appcompat:1.6.1`
  - `com.google.android.material:material:1.11.0` (Material 3)
  - `androidx.recyclerview:recyclerview:1.3.2`
  - `androidx.documentfile:documentfile:1.0.1` (SAF 深度支持)
  - `dev.rikka.shizuku:api:13.1.5` & `provider:13.1.5` (免Root目录穿透)

---

## 📁 项目目录结构

```
ComicReaderApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/comic/reader/     # 核心源码包
│   │   │   ├── MainActivity.java      # 主书架交互中枢与状态管理
│   │   │   ├── ReaderActivity.java    # 沉浸式阅读器与在线流式解码控制
│   │   │   ├── LanTransferManager.java# 局域网传输网络管理（HTTP + UDP）
│   │   │   ├── ComicShelfAdapter.java # 书架网格海报适配器（含在线书架模式）
│   │   │   ├── LanComicSelectAdapter.java # 互传选择器适配器
│   │   │   ├── ImageLoader.java       # 高性能内存缓存与大图下采样引擎
│   │   │   ├── ShelfStorage.java      # 书架本地持久化与物理文件安全操作
│   │   │   └── ...
│   │   ├── res/                       # Material 3 矢量图标、深色主题与布局
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

## 🚀 源码编译与调试

### 前置要求
1. 安装 [Android Studio](https://developer.android.com/studio) (推荐 Ladybug / Iguana 或更新版本) 或安装 JDK 17+ 与 Android SDK Platform 34。
2. 配置环境变量 `ANDROID_HOME` 指向您的 Android SDK 安装路径。

### 1. 克隆代码仓库
```bash
git clone https://github.com/yamada17kawa-lab/yyComicReaderApp.git
cd yyComicReaderApp
```

### 2. 命令行构建 Debug APK
```bash
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
chmod +x gradlew
./gradlew assembleDebug
```
构建成功后的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`。

### 3. 构建发布版 (Release)
```bash
.\gradlew.bat assembleRelease
```

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 开源发布。
