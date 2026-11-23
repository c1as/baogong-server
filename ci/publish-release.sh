#!/usr/bin/env bash
# ==============================================================================
# 创建 GitLab Release 并挂载 APK 与热更新包。
#
# 实现说明（为何不用 glab）：
#   glab 各版本的 release create 参数不一致（如 1.66 无 --yes，本机 1.131 有），
#   且 CLI 版本漂移会让流水线变得脆弱。故直接调用 GitLab REST API。
#
# 认证：使用 CI_JOB_TOKEN，通过 JOB-TOKEN 头传递。
#   Releases API 与 Generic Package Registry 均接受 JOB-TOKEN。
#   注意不要用 PRIVATE-TOKEN（那是 PAT 的头，Releases API 不接受）。
#
# 幂等：同 tag 重复执行会复用既有 Release 并更新其资产链接。
# ==============================================================================
set -euo pipefail

TAG="${CI_COMMIT_TAG:-}"
if [ -z "$TAG" ]; then
  echo "!! 当前不是 tag 流水线，跳过发布"
  exit 0
fi

PROJECT_DIR="${CI_PROJECT_DIR:-$(pwd)}"
cd "$PROJECT_DIR"

BASE_URL="${CI_SERVER_URL:-https://jihulab.com}"
PROJECT_ID="$CI_PROJECT_ID"
API="$BASE_URL/api/v4/projects/$PROJECT_ID"
JOB_TOKEN="$CI_JOB_TOKEN"

if [ -z "$JOB_TOKEN" ]; then
  echo "!! 缺少 CI_JOB_TOKEN，无法发布"
  exit 1
fi

# ---- 收集产物 ----
APKS="$(ls -1 dist/*.apk 2>/dev/null || true)"
ZIPS="$(ls -1 dist/*.zip 2>/dev/null || true)"
ASSETS=""
[ -n "$APKS" ] && ASSETS="$ASSETS $APKS"
[ -n "$ZIPS" ] && ASSETS="$ASSETS $ZIPS"

if [ -z "$(echo "$ASSETS" | tr -d ' ')" ]; then
  echo "!! 没有可发布的产物，跳过"
  exit 1
fi

echo "==> 待发布产物：$ASSETS"

PACKAGE_NAME="baogong-server"

# ---- 1. 上传到 Generic Package Registry ----
# 资产以包形式托管，Release 里用 link_type=package 指向它们
ASSET_LINKS=""
for f in $ASSETS; do
  fname="$(basename "$f")"
  encoded_name="$(printf '%s' "$fname" | sed 's/ /%20/g')"
  pkg_url="$API/packages/generic/$PACKAGE_NAME/$TAG/$encoded_name"

  echo "==> 上传 $fname"
  code=$(curl -sS -o /tmp/up.out -w "%{http_code}" -X PUT \
    -H "JOB-TOKEN: $JOB_TOKEN" \
    --upload-file "$f" \
    "$pkg_url")

  if [ "$code" != "201" ] && [ "$code" != "200" ]; then
    echo "!! 上传失败 HTTP $code"
    cat /tmp/up.out
    exit 1
  fi

  # 组装 asset link JSON（转义文件名中的引号与反斜杠）
  safe_name="${fname//\\/\\\\}"
  safe_name="${safe_name//\"/\\\"}"
  ASSET_LINKS="$ASSET_LINKS,{\"name\":\"$safe_name\",\"url\":\"$pkg_url\",\"link_type\":\"package\"}"
done
# 去掉开头的逗号
ASSET_LINKS="${ASSET_LINKS#,}"

# ---- 2. 生成发布说明 ----
NOTES_FILE="$PROJECT_DIR/dist/RELEASE_NOTES.md"
{
  echo "## 报工服务器 ${TAG}"
  echo
  echo "构建提交：\`${CI_COMMIT_SHORT_SHA}\`（分支 \`${CI_COMMIT_REF_NAME}\`）"
  echo
  echo "### 安装包（APK）"
  echo
  echo "安卓 arm64-v8a 安装包，已内置 PHP 运行时（\`libphp_bin.so\`）与报工系统程序。"
  echo
  echo "### 热更新包（zip）"
  echo
  echo "服务端 PHP 程序热更新包，zip 内文件位于根层级，可直接覆盖 \`public/\`。"
  echo
  echo "用法详见 [docs/03-热更新操作指南.md](${BASE_URL}/${CI_PROJECT_PATH}/-/blob/main/docs/03-热更新操作指南.md)："
  echo
  echo "1. **系统「文件」App**：侧边栏 → 报工服务器 → \`public/\` → 粘贴覆盖同名文件；"
  echo "2. **App 内导入**：菜单「导入文件更新」→ 选择本 zip 整包导入。"
  echo
  echo "> \`data/\`（数据库与上传图片）不在更新包内，历史数据不受影响。"
} > "$NOTES_FILE"

# 用 python/jq 不可用时，退化为手工拼 JSON 并转义
notes_json=$(sed 's/\\/\\\\/g; s/"/\\"/g' "$NOTES_FILE" | awk 'BEGIN{printf "\""} {if(NR>1) printf "\\n"; printf "%s", $0} END{printf "\""}')

# ---- 3. 创建或更新 Release ----
PAYLOAD=$(cat <<JSON
{
  "tag_name": "$TAG",
  "name": "报工服务器 $TAG",
  "description": $notes_json,
  "assets": {
    "links": [${ASSET_LINKS}]
  }
}
JSON
)

echo "==> 创建/更新 Release $TAG"
code=$(curl -sS -o /tmp/rel.out -w "%{http_code}" -X POST \
  -H "JOB-TOKEN: $JOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" \
  "$API/releases")

if [ "$code" != "201" ] && [ "$code" != "200" ]; then
  echo "!! Release 创建失败 HTTP $code"
  cat /tmp/rel.out
  exit 1
fi

echo "==> Release 就绪：${BASE_URL}/${CI_PROJECT_PATH}/-/releases/${TAG}"
