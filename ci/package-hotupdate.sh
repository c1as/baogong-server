#!/usr/bin/env bash
# ==============================================================================
# 打包「热更新包」——即手机端可导入的 zip。
#
# 依据 docs/03-热更新操作指南.md：
#   zip 内文件必须直接位于根层级（api.php、app.js、plugins/...），
#   不能多套一层文件夹，否则 App 内「导入文件更新」解压后目录结构会错位。
#
# 产物：dist/baogong-public-<version>.zip
# ==============================================================================
set -euo pipefail

PROJECT_DIR="${CI_PROJECT_DIR:-$(pwd)}"
SRC="$PROJECT_DIR/app/src/main/assets/baogong/public"
OUT_DIR="$PROJECT_DIR/dist"

cd "$PROJECT_DIR"

if [ ! -d "$SRC" ]; then
  echo "!! 找不到热更新源目录：$SRC"
  exit 1
fi

# 版本号优先取 app/build.gradle 的 versionName
VERSION="$(sed -n 's/.*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
VERSION="${VERSION:-unknown}"

mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/baogong-public-${VERSION}.zip"
rm -f "$ZIP"

# 在源目录内打包，保证 zip 内是根层级结构
cd "$SRC"
jar cfM "$ZIP" .
cd "$PROJECT_DIR"

echo ">>> 热更新包已生成：$ZIP"
ls -lh "$ZIP"
echo ">>> 包内结构预览（前 20 项）："
jar tf "$ZIP" | head -20
