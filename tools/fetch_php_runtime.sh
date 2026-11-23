#!/usr/bin/env bash
# ==============================================================================
# 拉取 Termux 交叉编译的 PHP 运行时，整理成 Android jniLibs 可直接使用的布局。
#
# 用法（在网络较好的电脑/服务器上执行，非手机）：
#   bash tools/fetch_php_runtime.sh arm64     # 64 位（绝大多数现代手机，推荐）
#   bash tools/fetch_php_runtime.sh arm       # 32 位 armv7
#
# 产物目录：app/src/main/jniLibs/<abi>/
#   libphp.so          <- PHP 可执行文件（ELF，重命名为 .so 才能被 Android 加载执行）
#   libpdo_sqlite.so   <- SQLite PDO 扩展（若 PHP 未内置）
#   libsqlite3.so
#   libmbstring.so
#   <其余依赖 .so>     <- libc++ / libcurl / openssl / libsqlite / oniguruma …
#
# 说明：
#  - Termux 的 PHP 依赖一组 .so，全部放进同一 jniLibs 目录，
#    App 启动时把该目录加入 LD_LIBRARY_PATH（见 PhpServer.java）。
#  - 脚本尽量自动解析依赖闭包；如个别库缺失，按 02-PHP运行时准备.md 手动补齐。
# ==============================================================================
set -euo pipefail

ABI="${1:-arm64}"
case "$ABI" in
  arm64) TERMUX_ARCH="aarch64" ; JNI_ABI="arm64-v8a" ;;
  arm)   TERMUX_ARCH="arm"     ; JNI_ABI="armeabi-v7a" ;;
  *) echo "用法: $0 [arm64|arm]"; exit 1 ;;
esac

BASE="https://packages.termux.dev/apt/termux-main"
POOL="$BASE/pool/main"
INDEX_URL="$BASE/dists/stable/main/binary-${TERMUX_ARCH}/Packages.gz"
WORK="$(cd "$(dirname "$0")" && pwd)/.php_runtime_work_${TERMUX_ARCH}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/${JNI_ABI}"

command -v curl >/dev/null || { echo "需要 curl"; exit 1; }
command -v dpkg-deb >/dev/null || command -v ar >/dev/null || { echo "需要 dpkg-deb 或 binutils(ar)"; exit 1; }
mkdir -p "$WORK" "$OUT"

echo "==> [1/5] 下载包索引: $INDEX_URL"
curl -fsSL --retry 3 "$INDEX_URL" -o "$WORK/Packages.gz"
gzip -dc "$WORK/Packages.gz" > "$WORK/Packages.txt"

# 解析某个包名对应的 Filename 与 Version
pkg_field() {
  awk -v pkg="$1" -v field="$2" '
    BEGIN{RS="";FS="\n"}
    { name="";fn="";ver="" }
    /^Package: /{name=$0;sub(/^Package: /,"",name)}
    /^Filename: /{fn=$0;sub(/^Filename: /,"",fn)}
    /^Version: /{ver=$0;sub(/^Version: /,"",ver)}
    name==pkg { if(field=="Filename") print fn; else if(field=="Version") print ver; }
  ' "$WORK/Packages.txt" | head -1
}

# 下载并解包一个 deb 到 $WORK/root
fetch_deb() {
  local pkg="$1" file ver url deb
  file="$(pkg_field "$pkg" Filename)"
  if [ -z "$file" ]; then echo "    ! 索引中找不到 $pkg，跳过"; return; fi
  deb="$WORK/$(basename "$file")"
  if [ ! -s "$deb" ]; then
    url="$POOL/$file"
    echo "    下载 $pkg -> $(basename "$deb")"
    curl -fsSL --retry 3 --retry-delay 2 "$url" -o "$deb" || { echo "    ! 下载失败 $pkg"; return; }
  fi
  mkdir -p "$WORK/root"
  if command -v dpkg-deb >/dev/null; then
    dpkg-deb -x "$deb" "$WORK/root"
  else
    ( cd "$WORK" && ar x "$deb" && tar -xf data.tar.* -C root )
  fi
}

echo "==> [2/5] 下载 php 主包及其依赖"
# 主包；其 Depends 里列出运行所需库，这里显式列出常见闭包
PKGS="php libc++ libandroid-support libandroid-glob libsqlite libcurl openssl \
      libxml2 libiconv libicu libffi libgmp libbz2 libzip libxslt oniguruma \
      pcre2 zlib readline libresolv-wrapper capstone tidy"
for p in $PKGS; do fetch_deb "$p"; done

echo "==> [3/5] 拷贝 PHP 可执行文件与所有 .so 到 $OUT"
ROOT="$WORK/root"
# PHP 可执行文件（Termux 路径 data/data/com.termux/files/usr/bin/php）
PHP_BIN=$(find "$ROOT" -type f -path '*/bin/php' | head -1)
if [ -z "$PHP_BIN" ]; then echo "!! 未找到 php 二进制，请检查包结构"; exit 1; fi
cp -f "$PHP_BIN" "$OUT/libphp.so"
chmod 755 "$OUT/libphp.so"

# 所有动态库（libphp 主程序自身是 ELF 可执行，依赖均为 .so）
# Termux 的 pdo_sqlite / sqlite3 / mbstring 已静态编译进 php 主二进制，通常无独立扩展 .so；
# 这里仍把解包出的所有 .so 一并拷入，兼容个别版本可能拆分出的扩展。
find "$ROOT" -type f \( -name '*.so' -o -name '*.so.*' \) | while read -r so; do
  base="$(basename "$so")"
  case "$base" in
    libphp.so) continue ;;
    *) cp -f "$so" "$OUT/$base" ;;
  esac
done

echo "==> [4/5] 处理带版本号的 .so（生成无版本软链，便于动态链接器解析）"
cd "$OUT"
for f in *.so.*; do
  [ -e "$f" ] || continue
  short="${f%%.so.*}.so"
  [ -e "$short" ] || ln -s "$f" "$short" 2>/dev/null || cp -f "$f" "$short"
done

echo "==> [5/5] 结果"
ls -lh "$OUT" | head -60
echo
echo "完成。请把 $OUT 整个目录纳入项目。"
echo "若 PHP 启动时报缺少某个 .so，请确认上面 PKGS 列表是否覆盖了它，补进列表后重跑本脚本。"
