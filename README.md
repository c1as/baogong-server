# 报工系统 · 安卓本机服务器版（baogong-server）

把原项目 [c1as/baogong-system v2.5](https://github.com/c1as/baogong-system/tree/v2.5) 从「群晖 / PC 上的 PHP 站点」改造成 **一台安卓手机即服务器** 的框架：

```
┌──────────────────────── 一台安卓手机 ────────────────────────┐
│  WebView  ──访问──▶  PHP 内置服务器(php -S) ──读写──▶ SQLite   │
│     ▲                      ▲                                    │
│     │ 127.0.0.1:8080       │ 覆盖 public/*.php                  │
│     │                 系统"文件"App ◀── 粘贴文件（DocumentsProvider）│
│     │                     或 App 内"导入文件"按钮                 │
└────────────────────────────────────────────────────────────────┘
```

**核心价值**：PHP 程序文件放在手机的可写目录里，通过系统自带的「文件」App 直接把新版 `public/api.php`、`app.js`、`plugins/` 等文件粘贴进去即可完成服务端升级 —— **不用连电脑、不用 WebDAV、不用重装 APK**。部分机型（如小米）隐藏了文件管理器侧边栏入口时，还可点 App 内的「导入文件」按钮兜底。

> 原仓库自带的 `android/` 只是「填写远程服务器地址 → WebView 加载」的瘦客户端，本机并不跑 PHP。本项目在其基础上重建为**本机服务器架构**。

## 目录结构

```
BaogongServer/
├── app/src/main/
│   ├── java/com/baogong/server/
│   │   ├── ServerApp.java          应用入口、通知渠道
│   │   ├── Config.java             路径/端口配置（热更新核心约定）
│   │   ├── MainActivity.java       WebView 控制台 + 导入文件入口
│   │   ├── ServerService.java      前台服务，托管 PHP
│   │   ├── provider/
│   │   │   └── BaogongDocumentsProvider.java  把私有目录挂载到系统"文件"App
│   │   ├── installer/
│   │   │   ├── AssetInstaller.java 首次启动解压程序到可写目录
│   │   │   └── HotUpdateImporter.java  导入 php/zip 覆盖 public/（兜底）
│   │   └── server/
│   │       ├── PhpServer.java      php -S 进程管理 + 自动拉起
│   │       └── NetUtils.java       获取本机 IP
│   ├── assets/baogong/             内置的报工系统 v2.5 原始程序（首次安装用）
│   │   ├── public/                 api.php / app.js / index.html / plugins/ …
│   │   └── app.env.example
│   ├── jniLibs/arm64-v8a/          PHP 二进制(libphp_bin.so) + 34 个依赖 .so
│   │                               （v1.3 起放这里，安装到 nativeLibraryDir 可执行目录）
│   └── res/                        界面资源、图标、网络安全配置
├── tools/
│   └── fetch_php_runtime.sh        从 Termux 仓库拉取 PHP 运行时到 jniLibs
└── docs/
    ├── 01-架构说明.md
    ├── 02-PHP运行时准备.md
    ├── 03-热更新操作指南.md
    └── 04-打包APK指南.md
```

## 快速开始（使用者）

1. 用 Android Studio 打开本项目（首次会下载 Gradle 与依赖，需联网）。
2. 按 [`docs/02-PHP运行时准备.md`](docs/02-PHP运行时准备.md) 把 PHP 运行时放进 `app/src/main/jniLibs/`（只需做一次）。
3. 手机开 USB 调试，点 ▶ 运行；或按 [`docs/04-打包APK指南.md`](docs/04-打包APK指南.md) 打 release APK 安装。
4. App 启动后顶部显示 PHP 状态，下方即为报工系统界面。
5. 按 [`docs/03-热更新操作指南.md`](docs/03-热更新操作指南.md)：打开系统「文件」App 在侧边栏「报工服务器」中覆盖 `public/` 下的文件，或点 App 内「导入文件更新」选 php/zip，即可升级服务端。

## 一句话原理

- **Web 程序**（`public/`）解压到 `filesDir/baogong/public`（可写），WebView 加载 `http://127.0.0.1:8080`。
- **PHP** 用内置服务器 `php -S`，每条请求重新读脚本 → 覆盖文件立即生效。
- **数据**（`data/`）永不随升级覆盖，保护数据库与图片。
- **热更新** 通过 DocumentsProvider 把 `filesDir/baogong` 挂到系统「文件」App，直接粘贴文件覆盖 `public/`；App 内「导入文件」支持 php/zip 兜底。

## 版本历史

| 版本 | 说明 |
|------|------|
| v1.0 | 首个可构建版本 |
| v1.1 | 修复 Android 14 前台服务类型权限缺失导致的闪退 |
| v1.2 | 尝试把 PHP 运行时改到 assets 解压（保留带版本号 SONAME）——**该路径在 Android 10+ 因 noexec 无法执行** |
| **v1.3** | **彻底修复启动问题**：PHP 运行时（二进制 + 34 个依赖）改由 jniLibs 安装到 nativeLibraryDir；patchelf 规范化所有 SONAME；补齐缺失依赖；启用 extractNativeLibs |
| v1.4 | 改用原地二进制替换 SONAME（不用 patchelf），修复 Android 16 上的 SIGSEGV 崩溃 |
| v1.5 | 修复 php.ini 中 `~~` 路径导致的语法错误与 opcache 致命错误，报工系统正常运行 |
| v1.6 | WebDAV 增加网页版文件管理器（后被 v2.0 取代） |
| **v2.0** | **热更新重构**：移除 WebDAV，改用 DocumentsProvider 把私有目录挂载到系统「文件」App，直接粘贴文件即可更新；新增 App 内「导入文件」（php/zip）兜底 |

## 打包产物（已实际构建成功）

- **正式版 APK**：`app/build/outputs/apk/release/app-release.apk`（约 30MB，arm64-v8a，正式签名，可直接覆盖安装）
- 已内置 PHP 8.5 运行时与报工系统 v2.5 全套程序
- 下载页：GitLab Release `https://jihulab.com/c1as/baogong-server/-/releases`（私有仓库，需先登录）
- 重新构建：`./gradlew assembleRelease`（见 `docs/04-打包APK指南.md`）

## 已知边界

- APK 为 **arm64（64 位）**，32 位 armv7 老机型不适用（可用 `fetch_php_runtime.sh arm` 重新打包）。
- PHP 运行时来自 Termux 交叉编译包，已随 APK 打包，无需用户单独准备。
- 仅做本地打包与部署，不涉及应用商店上架。
