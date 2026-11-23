#!/usr/bin/env bash
# ==============================================================================
# CI 中准备 release 签名配置。
#
# 安全约定：签名密钥与密码【绝不入库】。CI 通过 CI/CD 变量注入：
#   KEYSTORE_BASE64  —— 密钥库文件的 base64 内容（建议设为 Masked + Protected）
#   KEYSTORE_PASSWORD —— 密钥库口令
#   KEY_ALIAS        —— 密钥别名
#   KEY_PASSWORD     —— 密钥口令
#
# 变量缺失时静默跳过（不生成 keystore.properties），
# 此时 release 构建走 AGP 的 debug 签名，流水线仍然成功。
# ==============================================================================
set -euo pipefail

PROJECT_DIR="${CI_PROJECT_DIR:-$(pwd)}"

# 占位值视为未配置（CI 变量已建好但尚未填入真实密钥时）
is_unset() {
  local v="${1:-}"
  [ -z "$v" ] && return 0
  case "$v" in
    PLACEHOLDER|placeholder|CHANGEME|changeme) return 0 ;;
  esac
  return 1
}

if is_unset "${KEYSTORE_BASE64:-}"; then
  echo ">>> 未配置 KEYSTORE_BASE64（或仍是占位值），跳过签名配置"
  echo ">>> release 将回退到 debug 签名；配置方法见 docs/07-CI/CD指南.md"
  exit 0
fi

if is_unset "${KEYSTORE_PASSWORD:-}" || is_unset "${KEY_ALIAS:-}" || is_unset "${KEY_PASSWORD:-}"; then
  echo ">>> KEYSTORE_BASE64 已设置但密码/别名为空（或仍是占位值），跳过签名配置"
  exit 0
fi

cd "$PROJECT_DIR"
umask 077

echo "$KEYSTORE_BASE64" | base64 -d > release-keystore.jks

cat > keystore.properties <<EOF
storeFile=release-keystore.jks
storePassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF

echo ">>> 签名配置已生成（密钥文件不会提交，仅存在于本次作业工作区）"
