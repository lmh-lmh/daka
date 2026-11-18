#!/bin/bash
# 一键把本仓库发到 GitHub。
#
# 用法：
#   bash tools/push_to_github.sh <你的GitHub用户名> [仓库名]
#
# 前提：把 Personal Access Token 写进 ~/.daka-github-token（只有 token 一行）。
#   printf 'ghp_xxxx\n' > ~/.daka-github-token && chmod 600 ~/.daka-github-token
#
# 为什么 token 走文件而不是命令行参数：
#   1. 命令行参数会进 shell history
#   2. 贴进对话会留在会话记录里
#   3. 写进 .git/config 的 remote URL 会明文躺在仓库里
# 本脚本用 http.extraheader 临时传认证，token 不落任何文件、不进 history。
set -e

GH_USER="${1:?用法: bash tools/push_to_github.sh <GitHub用户名> [仓库名]}"
REPO="${2:-daka}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.daka-github-token}"

if [ ! -f "$TOKEN_FILE" ]; then
  cat >&2 <<EOF
ERROR: 找不到 token 文件：$TOKEN_FILE

请先执行（把 ghp_xxxx 换成你的 token）：

  printf 'ghp_xxxx\n' > ~/.daka-github-token
  chmod 600 ~/.daka-github-token

token 在这里生成：https://github.com/settings/tokens
勾选 repo 权限即可。
EOF
  exit 1
fi

TOKEN="$(tr -d ' \r\n\t' < "$TOKEN_FILE")"
[ -n "$TOKEN" ] || { echo "ERROR: token 文件是空的" >&2; exit 1; }

API="https://api.github.com"
AUTH_HEADER="Authorization: Bearer $TOKEN"

echo "== 1/4 验证 token =="
WHOAMI_JSON=$(curl -s -H "$AUTH_HEADER" -H "Accept: application/vnd.github+json" "$API/user")
WHOAMI=$(printf '%s' "$WHOAMI_JSON" | sed -n 's/.*"login"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
if [ -z "$WHOAMI" ]; then
  echo "ERROR: token 无效或权限不足。GitHub 返回：" >&2
  printf '%s\n' "$WHOAMI_JSON" | head -c 400 >&2
  echo >&2
  exit 1
fi
echo "   已认证为：$WHOAMI"
if [ "$WHOAMI" != "$GH_USER" ]; then
  echo "   注意：token 属于 $WHOAMI，不是你给的 $GH_USER —— 以 token 为准" >&2
fi

echo "== 2/4 创建仓库 $WHOAMI/$REPO =="
CREATE_JSON=$(curl -s -X POST \
  -H "$AUTH_HEADER" -H "Accept: application/vnd.github+json" \
  "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"description\":\"Android 地图打卡 App：分层足迹、卫星底图、原图拍照、数字聚合\",\"private\":false,\"has_issues\":true,\"has_wiki\":false}")
if printf '%s' "$CREATE_JSON" | grep -q '"full_name"'; then
  echo "   创建成功"
elif printf '%s' "$CREATE_JSON" | grep -q 'already exists'; then
  echo "   仓库已存在，继续推送"
else
  echo "   建仓库可能失败，返回：" >&2
  printf '%s\n' "$CREATE_JSON" | head -c 400 >&2
  echo >&2
  echo "   （如果仓库已存在，可忽略，继续推送）" >&2
fi

echo "== 3/4 推送 =="
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$WHOAMI/$REPO.git"

# 关键：认证走临时 header，token 不进 remote URL、不写 .git/config
BASIC=$(printf '%s:x-oauth-basic' "$TOKEN" | base64 | tr -d '\n')
git -c http.extraheader="AUTHORIZATION: basic $BASIC" \
    push -u origin HEAD:refs/heads/main --force 2>&1 | tail -8

# 推完把 remote 恢复成干净 URL（本来就没带 token，这里只是确保）
git remote set-url origin "https://github.com/$WHOAMI/$REPO.git"

echo "== 4/4 清理 =="
echo "   建议现在去 GitHub 删掉这个 token：https://github.com/settings/tokens"
echo "   删掉后本机文件也可以删：rm -f $TOKEN_FILE"
echo
echo "完成：https://github.com/$WHOAMI/$REPO"
