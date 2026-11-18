#!/bin/bash
# 诊断 GitHub token 到底有哪些权限，能不能建仓库、能不能推送。
#
# 用法：bash tools/check_token.sh
#   （读 ~/.daka-github-token；不存在会提示先录入）
#
# 为什么要这个：token 权限不对时报错信息很含糊（403/404 混着来），
# 光看 push 失败不知道是哪种问题。这里逐项探，给出能直接照做的结论。
set -u

TOKEN_FILE="${TOKEN_FILE:-$HOME/.daka-github-token}"
API="https://api.github.com"

if [ ! -s "$TOKEN_FILE" ]; then
  echo "没有找到 token 文件：$TOKEN_FILE"
  echo "先把 token 存进去：printf 'ghp_xxxx\\n' > ~/.daka-github-token"
  exit 1
fi

TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
AUTH="Authorization: Bearer $TOKEN"
GH="Accept: application/vnd.github+json"

echo "=== 1. token 类型 ==="
case "$TOKEN" in
  ghp_*) echo "   经典 token（classic），前缀 ghp_" ;;
  github_pat_*) echo "   细粒度 token（fine-grained），前缀 github_pat_" ;;
  gho_*|ghu_*|ghs_*) echo "   其他类型（OAuth/用户/服务），前缀 ${TOKEN%%_*}" ;;
  *) echo "   前缀不认识，可能复制不完整（长度 ${#TOKEN}）" ;;
esac

echo
echo "=== 2. 认证是否有效 ==="
RESP=$(curl -s -H "$AUTH" -H "$GH" "$API/user")
LOGIN=$(printf '%s' "$RESP" | sed -n 's/.*"login"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
if [ -n "$LOGIN" ]; then
  echo "   有效，身份是：$LOGIN"
else
  echo "   无效。GitHub 返回："
  printf '%s\n' "$RESP" | head -c 200 | sed 's/^/     /'
  echo
  exit 1
fi

echo
echo "=== 3. token 声明的 scope（经典 token 才显示）==="
HDR=$(curl -s -I -H "$AUTH" "$API/user" | grep -i '^x-oauth-scopes:')
if [ -n "$HDR" ]; then
  echo "   ${HDR#*: }"
else
  echo "   （没有这个头，通常是细粒度 token）"
fi

echo
echo "=== 4. 能不能读你自己的仓库列表 ==="
CODE=$(curl -s -o /tmp/_gh_repos.json -w '%{http_code}' -H "$AUTH" -H "$GH" "$API/user/repos?per_page=1")
echo "   HTTP $CODE"
case "$CODE" in
  200) echo "   可以读" ;;
  403|404) echo "   不能读 —— 说明 token 没有 repo 权限" ;;
  *) echo "   异常" ;;
esac

echo
echo "=== 5. 能不能建仓库（这是关键）==="
echo "   用一次真实创建请求试（同名已存在不会失败，只会提示）"
CODE=$(curl -s -o /tmp/_gh_create.json -w '%{http_code}' -X POST \
  -H "$AUTH" -H "$GH" "$API/user/repos" \
  -d '{"name":"daka-permission-test","private":true,"description":"permission probe, safe to delete"}')
echo "   HTTP $CODE"
case "$CODE" in
  201)
    echo "   可以建。顺手把测试仓库删掉……"
    curl -s -o /dev/null -X DELETE -H "$AUTH" "$API/repos/$LOGIN/daka-permission-test"
    echo "   已删除测试仓库"
    ;;
  422)
    echo "   已经存在（或名字被占），看作可以建" ;;
  403)
    echo "   不能建 —— 权限不足。返回片段："
    printf '%s\n' "$(cat /tmp/_gh_create.json)" | head -c 250 | sed 's/^/     /'
    echo
    ;;
  404)
    echo "   不能建 —— token 无权访问该资源（细粒度 token 常见：没选仓库或权限为空）"
    ;;
  *)
    echo "   异常，返回："
    printf '%s\n' "$(cat /tmp/_gh_create.json)" | head -c 250 | sed 's/^/     /'
    echo
    ;;
esac

echo
echo "=== 结论 ==="
if [ "$CODE" = "201" ] || [ "$CODE" = "422" ]; then
  echo "   这个 token 可以用来发布。跑：bash tools/publish.sh $LOGIN daka"
else
  cat <<'EOF'
   这个 token 权限不够。两种修法：

   【推荐】改用经典 token（classic）：
     1. https://github.com/settings/tokens
     2. 点右上 "Generate new token" → 选 "Generate new token (classic)"
        （注意：不是 Fine-grained tokens）
     3. Note 随便写，Expiration 选 30 days
     4. Scopes 只勾第一个大项 "repo"
     5. Generate token，复制 ghp_ 开头那串（只显示一次）
     6. printf 'ghp_xxxx\n' > ~/.daka-github-token && chmod 600 ~/.daka-github-token

   【或者】修好这个细粒度 token：
     1. 在你截图那个页面点 "编辑"
     2. Repository access 选 "All repositories"
     3. Repository permissions 里把 "Administration" 设为 Read and write
        （建仓库必须要这个；推送代码还要 "Contents: Read and write"）
     4. 保存后点 "Regenerate token"，新值会显示一次，复制它
EOF
fi
