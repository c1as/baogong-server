#!/usr/bin/env bash
# ==============================================================================
# 在 CI 容器内准备 Android 构建工具链（Android SDK + Gradle 分发包）。
#
# 背景（均为 JihuLab Runner 实测结论，勿随意改动）：
#   1. Runner 节点位于国内，maven.google.com 不可达（超时），
#      因此 Gradle 依赖必须走镜像（见 settings.gradle）。
#   2. gradle-wrapper 直连 services.gradle.org 会跳 GitHub 并读超时，
#      故改为从腾讯云镜像预下载并解压，缓存复用。
#   3. 容器内无 unzip/python3，用 JDK 自带的 jar 解压；
#      解压产物默认无可执行权限，必须显式 chmod。
#
# 产出：
#   $CI_PROJECT_DIR/.android-sdk/   Android SDK（platform 35 / build-tools 35）
#   $CI_PROJECT_DIR/.gradle-dist/   Gradle 发行版
#
# 用法：source 本脚本，或直接执行（会自动 export 环境变量）
# ==============================================================================
set -euo pipefail

PROJECT_DIR="${CI_PROJECT_DIR:-$(pwd)}"
ANDROID_HOME="$PROJECT_DIR/.android-sdk"
ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE_VERSION="8.14.3"
GRADLE_DIST_DIR="$PROJECT_DIR/.gradle-dist"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
GRADLE_MIRROR_URL="https://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip"

export ANDROID_HOME ANDROID_SDK_ROOT
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$GRADLE_DIST_DIR/gradle-$GRADLE_VERSION/bin"

# ---------- 1. Android SDK 命令行工具 ----------
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> 安装 Android cmdline-tools"
  cd "$ANDROID_HOME/cmdline-tools"
  rm -rf latest cmdline-tools cli.zip
  curl -fsSL --retry 3 --connect-timeout 30 -o cli.zip "$CMDLINE_TOOLS_URL"
  jar xf cli.zip
  mv cmdline-tools latest
  rm -f cli.zip
  chmod -R +x "$ANDROID_HOME/cmdline-tools/latest/bin"
fi

# ---------- 2. 接受许可并安装组件 ----------
# licenses 目录存在即视为已授权，避免每次重复交互
if [ ! -d "$ANDROID_HOME/licenses" ]; then
  echo "==> 接受 SDK 许可"
  yes | sdkmanager --licenses > /dev/null 2>&1 || true
fi

echo "==> 安装 platform-tools / platforms;android-35 / build-tools;35.0.0"
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /dev/null 2>&1

# ---------- 3. Gradle 发行包（镜像预下载） ----------
mkdir -p "$GRADLE_DIST_DIR"
if [ ! -x "$GRADLE_DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  echo "==> 下载 Gradle $GRADLE_VERSION（$GRADLE_MIRROR_URL）"
  cd "$GRADLE_DIST_DIR"
  rm -rf "gradle-$GRADLE_VERSION" gradle.zip
  curl -fsSL --retry 3 --connect-timeout 30 -o gradle.zip "$GRADLE_MIRROR_URL"
  jar xf gradle.zip
  rm -f gradle.zip
  chmod -R +x "$GRADLE_DIST_DIR/gradle-$GRADLE_VERSION/bin"
fi

cd "$PROJECT_DIR"
echo "==> 工具链就绪："
echo "    ANDROID_HOME=$ANDROID_HOME"
echo "    gradle     =$(command -v gradle)"
echo "    sdkmanager=$(command -v sdkmanager)"
