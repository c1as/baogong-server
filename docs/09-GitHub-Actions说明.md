# 09 · GitHub Actions 工作流说明

> 工作流文件：`.github/workflows/build-release.yml`
> 对应仓库：`https://github.com/c1as/baogong-server`（Public）
> 创建日期：2026-11-23

---

## 1. 这个工作流做什么

把原来极狐 GitLab 的流水线能力，搬到了 GitHub Actions。三个 job 依次执行：

| Job | 触发 | 作用 |
|---|---|---|
| `check` | 所有推送 / PR | PHP 语法检查 + 关键文件校验 |
| `build` | 所有推送 / PR | 构建 release APK + 打热更新 zip，上传为 artifacts |
| `release` | 仅 `v*` tag | 创建 GitHub Release，挂载 APK 与 zip |

日常推代码到 `main` 或提 PR，会跑 check + build；要发版时打一个 `v*` tag 推送，才会额外触发 release。

---

## 2. 与原 GitLab 流水线的差异（为什么改）

| 项目 | 极狐 GitLab 原版 | GitHub Actions 新版 |
|---|---|---|
| Runner | 国内节点，Google/Maven 不可达，需镜像 + 自下载工具链 | GitHub 官方 runner，可直连，用官方 setup 动作，删掉了整套镜像 hack |
| 发布 | GitLab Generic Package + JOB-TOKEN（私有仓库直链 401 的 workaround） | GitHub Release 原生资产，用 `softprops/action-gh-release` + 内置 `GITHUB_TOKEN` |
| 签名注入 | CI/CD Variables | GitHub Secrets |
| PHP / 结构检查 | 自定义脚本 | 保留同等逻辑，写在 workflow 内 |

产物、版本号、签名机制都不变，App 端和热更新流程无需任何改动。

---

## 3. 首次配置：4 个签名 Secrets（重要）

签名密钥**绝不入库**。在工作流里通过 Secrets 注入，同名沿用原来的 4 个：

进入仓库 **Settings → Secrets and variables → Actions → New repository secret**，添加：

| Secret 名 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 签名文件 `baogong-release.jks` 的 base64 内容 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 密钥别名（如原来那个 7 字符别名） |
| `KEY_PASSWORD` | 密钥口令 |

### 生成 KEYSTORE_BASE64

在本机（有 jks 文件的机器）执行：

```bash
# Linux / macOS
base64 -w0 baogong-release.jks

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("baogong-release.jks"))
```

把输出的一长串整体复制为 `KEYSTORE_BASE64` 的值。

> **务必用原来那个正式签名 jks**，不要新生成。换签名会导致手机上已装版本无法覆盖升级（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装。

### 不配 Secrets 会怎样

`ci/prepare-signing.sh` 检测到没配置会跳过，release 自动回退 **debug 签名**：流水线照样成功、APK 照样能装，但**签名与已发布的正式版不同，无法覆盖安装**。所以正式发版前请先配好这 4 个 Secrets。

---

## 4. 发版流程

```bash
# 1. 确认 app/build.gradle 里 versionCode / versionName 已升到目标版本
# 2. 提交推送到 main
git add -A && git commit -m "feat: v2.4 ..." && git push origin main

# 3. 等 main 上的 check + build 全绿

# 4. 打 tag 推送（v* 才会触发 Release）
git tag -a v2.4 -m "v2.4" && git push origin v2.4
```

打完 tag 后，`release` job 会自动创建 GitHub Release，并把以下资产挂上去：

- `baogong-server-<version>-arm64.apk` — 安卓安装包
- `baogong-public-<version>.zip` — 服务端热更新包

发布页：`https://github.com/c1as/baogong-server/releases`

---

## 5. 产物与下载

- 每次 build 的 APK/zip 也在对应 workflow run 的 **Artifacts** 里（保留 90 天）；
- 正式发布的以 Release 资产为准；
- 热更新 zip 内文件在根层级（`api.php`、`app.js`、`plugins/...`），可直接用 App 内「导入文件更新」整包导入。

---

## 6. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| release job 没触发 | tag 必须以 `v` 开头（如 `v2.4`）；`web-v*` 是服务端热更新 tag，不触发此工作流 |
| APK 无法覆盖安装 | Secrets 没配或用了新签名，导致 debug 签名；补齐 4 个 Secrets 并用原 jks 重跑 |
| `sdkmanager` 相关报错 | GitHub runner 已预装 Android SDK；如仍缺组件，确认 `setup-android` 与 sdkmanager 步骤正常 |
| `php -l` 报语法错 | 检查 assets 内 PHP；这是真错误，修完再推 |
| Release 资产为空 | 检查 build job 是否正常产出 dist/；release job 依赖 build 的 artifacts |
| 国内访问 GitHub Actions 慢 | runner 在境外，首次下载依赖可能较慢，属正常；后续有 Gradle 缓存会快很多 |

---

## 7. 与原 `ci/` 脚本的关系

工作流复用了两个脚本：

- `ci/prepare-signing.sh` — 从环境变量还原签名（GitHub Secrets 注入的环境变量名与原 CI 变量一致，直接兼容）；
- `ci/package-hotupdate.sh` — 打热更新 zip（用 `CI_PROJECT_DIR:-$(pwd)` 兜底，GitHub 环境取当前目录，兼容）。

另外两个脚本在 GitHub 上**不再使用**：

- `ci/setup-android-toolchain.sh` — GitLab 国内节点的镜像/自下载方案，GitHub runner 不需要；
- `ci/publish-release.sh` — GitLab 专有 API（JOB-TOKEN / Generic Package），GitHub 用 `softprops/action-gh-release` 替代。
