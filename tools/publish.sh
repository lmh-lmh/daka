#!/bin/bash
# 一条命令走完：录入 token → 建仓库 → 推送。
#
# 用法（Git Bash）：
#   bash tools/publish.sh lmh-lmh daka
#
# 会做三件事：
#   1. 如果还没有 token 文件，提示你粘贴（不回显、不进 history）
#   2. 用 GitHub API 建 public 仓库（已存在就跳过）
#   3. 推送 main 分支
#
# token 全程只存在于本机文件（权限 600），不进 shell history、不进对话、不进 .git/config。
set -e

GH_USER="${1:?用法: bash tools/publish.sh <GitHub用户名> [仓库名]}"
REPO="${2:-daka}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.daka-github-token}"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# ---------- 1. token ----------
if [ ! -s "$TOKEN_FILE" ]; then
  echo "==============================================="
  echo "还没找到 token，现在录入。"
  echo "生成地址：https://github.com/settings/tokens"
  echo "  → Generate new token (classic)"
  echo "  → 勾选 repo（其他不用勾）"
  echo "  → Generate token，复制那串 ghp_ 开头的"
  echo "==============================================="
  echo
  echo "把 token 粘贴到这里后回车（不会显示）："
  IFS= read -rs TOKEN
  echo
  TOKEN="$(printf '%s' "$TOKEN" | tr -d ' \t\r\n')"
  [ -n "$TOKEN" ] || { echo "ERROR: 没输入内容" >&2; exit 1; }
  umask 077
  printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE" 2>/dev/null || true
  echo "已保存到 $TOKEN_FILE"
  echo
fi

TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
[ -n "$TOKEN" ] || { echo "ERROR: token 文件是空的" >&2; exit 1; }

API="https://api.github.com"
AUTH="Authorization: Bearer $TOKEN"

# ---------- 2. 验证 token ----------
echo "== 1/3 验证 token =="
WHOAMI_JSON=$(curl -s -H "$AUTH" -H "Accept: application/vnd.github+json" "$API/user")
WHOAMI=$(printf '%s' "$WHOAMI_JSON" | sed -n 's/.*"login"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
if [ -z "$WHOAMI" ]; then
  echo "ERROR: token 无效。GitHub 返回：" >&2
  printf '%s\n' "$WHOAMI_JSON" | head -c 300 >&2
  echo >&2
  echo "常见原因：token 复制不完整 / 没勾 repo 权限 / 已过期" >&2
  exit 1
fi
echo "   认证为：$WHOAMI"

# ---------- 3. 建仓库 ----------
echo "== 2/3 确保仓库 $WHOAMI/$REPO 存在 =="
CREATE_JSON=$(curl -s -X POST \
  -H "$AUTH" -H "Accept: application/vnd.github+json" \
  "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"description\":\"Android 地图打卡 App：分层足迹、卫星底图、原图拍照、数字聚合\",\"private\":false,\"has_issues\":true,\"has_wiki\":false}")

if printf '%s' "$CREATE_JSON" | grep -q '"full_name"'; then
  echo "   已创建"
elif printf '%s' "$CREATE_JSON" | grep -q 'already exists'; then
  echo "   已存在，直接推送"
else
  # 有些情况返回 422 但仓库其实被别的方式建好了，先探一下
  PROBE=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$API/repos/$WHOAMI/$REPO")
  if [ "$PROBE" = "200" ]; then
    echo "   已存在（探测确认），直接推送"
  else
    echo "   建仓库失败：" >&2
    printf '%s\n' "$CREATE_JSON" | head -c 300 >&2
    echo >&2
    exit 1
  fi
fi

# ---------- 4. 推送 ----------
echo "== 3/3 推送 main 分支 =="
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$WHOAMI/$REPO.git"

BASIC=$(printf '%s:x-oauth-basic' "$TOKEN" | base64 | tr -d '\n')
# 认证走临时 header：token 不进 remote URL、不写 .git/config、不进 history
git -c http.extraheader="AUTHORIZATION: basic $BASIC" \
    push -u origin HEAD:refs/heads/main --force 2>&1 | tail -6

git remote set-url origin "https://github.com/$WHOAMI/$REPO.git"

echo
echo "==============================================="
echo "完成：https://github.com/$WHOAMI/$REPO"
echo
echo "收尾建议（可选）："
echo "  1. 去 https://github.com/settings/tokens 删掉这个 token"
echo "  2. 删掉本机文件：rm -f $TOKEN_FILE"
echo "==============================================="
