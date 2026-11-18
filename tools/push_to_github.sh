#!/bin/bash
# 把本仓库推送到 GitHub。token 从本地文件读，不写死在脚本里、不出现在命令行参数里。
#
# 用法：
#   1) 把 token 写进 $TOKEN_FILE（默认 ~/.daka-github-token），文件里只有 token 一行
#   2) bash tools/push_to_github.sh <你的GitHub用户名> [仓库名]
#
# 脚本做的事：
#   1) 用 token 建仓库（已存在则跳过）
#   2) 配 remote 并推送
#   3) 推完后把 remote 改回不含 token 的干净 URL
set -e

GH_USER="${1:?用法: bash tools/push_to_github.sh <GitHub用户名> [仓库名]}"
REPO="${2:-daka}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.daka-github-token}"

if [ ! -f "$TOKEN_FILE" ]; then
  echo "ERROR: 找不到 token 文件 $TOKEN_FILE" >&2
  echo "请把 token 单独一行写进那个文件，然后 chmod 600" >&2
  exit 1
fi

TOKEN="$(tr -d ' \r\n' < "$TOKEN_FILE")"
if [ -z "$TOKEN" ]; then
  echo "ERROR: token 文件是空的" >&2
  exit 1
fi

API="https://api.github.com"

echo "== 1/3 检查 token 有效性 =="
WHOAMI=$(curl -s -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$API/user" \
         | grep -o '"login"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
if [ -z "$WHOAMI" ]; then
  echo "ERROR: token 无效或没权限（拿不到 /user）" >&2
  exit 1
fi
echo "   登录为: $WHOAMI"

echo "== 2/3 创建仓库 $WHOAMI/$REPO（已存在则跳过）=="
HTTP=$(curl -s -o /tmp/_gh_create.json -w '%{http_code}' \
  -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"description\":\"Android 地图打卡 App：分层足迹、卫星底图、原图拍照\",\"private\":false,\"has_issues\":true,\"has_wiki\":false}")
if [ "$HTTP" = "201" ]; then
  echo "   创建成功"
elif [ "$HTTP" = "422" ]; then
  echo "   仓库已存在，继续"
else
  echo "ERROR: 建仓库失败 HTTP=$HTTP" >&2
  head -c 500 /tmp/_gh_create.json >&2
  exit 1
fi

echo "== 3/3 推送 =="
# 用 remote 里只带用户名、token 走临时 header，避免 token 落进 .git/config
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$WHOAMI/$REPO.git"

# 用 http.extraheader 传认证，token 不进入 remote URL、不写盘
git -c http.extraheader="AUTHORIZATION: basic $(printf '%s:x-oauth-basic' "$TOKEN" | base64 -w0)" \
    push -u origin HEAD:refs/heads/main

# 确保 remote URL 干净（不含 token）
git remote set-url origin "https://github.com/$WHOAMI/$REPO.git"

echo
echo "完成: https://github.com/$WHOAMI/$REPO"
