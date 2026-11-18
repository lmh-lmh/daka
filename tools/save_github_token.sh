#!/bin/bash
# 把 GitHub token 存到本机供推送脚本读取。
#
# 为什么要有这个脚本：
#   token 写进 shell history 或对话记录都会长期留存。这个脚本从**交互式输入**读取
#   （输入时不回显、不进 history），直接写进权限 600 的文件。
#
# 用法（在 Git Bash 里执行）：
#   bash tools/save_github_token.sh
#
# 存好之后跑：
#   bash tools/push_to_github.sh lmh-lmh daka
#
# 用完想清掉：
#   rm -f ~/.daka-github-token
set -e

TOKEN_FILE="${TOKEN_FILE:-$HOME/.daka-github-token}"

echo "把 GitHub Personal Access Token 粘贴到这里然后回车。"
echo "（输入内容不会显示在屏幕上，也不会进 shell history）"
echo "生成地址：https://github.com/settings/tokens → Generate new token (classic)，勾 repo"
echo

# -s 不回显；-r 不把反斜杠当转义
IFS= read -rs TOKEN
echo

# 去掉可能的空白和换行
TOKEN="$(printf '%s' "$TOKEN" | tr -d ' \t\r\n')"

if [ -z "$TOKEN" ]; then
  echo "ERROR: 什么都没输入" >&2
  exit 1
fi

# 粗校验：GitHub token 的常见前缀
case "$TOKEN" in
  ghp_*|gho_*|ghu_*|ghs_*|github_pat_*) ;;
  *)
    echo "警告：这不像常见的 GitHub token 前缀（ghp_ / github_pat_ 等）。" >&2
    echo "      仍然会保存，但如果推送失败请检查是否复制完整。" >&2
    ;;
esac

# 写文件，权限 600（只有自己能读）
umask 077
printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
chmod 600 "$TOKEN_FILE" 2>/dev/null || true

echo "已保存到 $TOKEN_FILE"
echo "长度：$(wc -c < "$TOKEN_FILE") 字节（含换行）"
echo
echo "下一步：bash tools/push_to_github.sh lmh-lmh daka"
