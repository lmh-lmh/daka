#!/bin/bash
# 创建 GitHub Release 并上传 APK。
#
# 用法：bash tools/publish_release.sh [tag] [APK文件路径]
#   默认 tag=v4.1  APK=打卡-v4.1.apk
#
# 为什么走 Release 而不是只放仓库里：
#   1. 别人不用 clone 55 MB 就能直接下载 APK
#   2. Release 下载走 CDN，比拉仓库快
#
# 【注意：本脚本刻意不用反斜杠续行】
#   MSYS/Git-Bash 下 `\` 后面跟一个空格就会断掉命令（实测踩过：
#   报 "curl: (2) no URL specified"）。所以这里全部写成单行或变量拼装。
set -e

TAG="${1:-v4.1}"
APK="${2:-打卡-v4.1.apk}"
REPO_SLUG="lmh-lmh/daka"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.daka-github-token}"
API="https://api.github.com"
UPLOAD_API="https://uploads.github.com"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

[ -s "$TOKEN_FILE" ] || { echo "ERROR: 没有 token 文件 $TOKEN_FILE" >&2; exit 1; }
[ -f "$APK" ] || { echo "ERROR: 找不到 $APK" >&2; exit 1; }

TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
AUTH="Authorization: Bearer $TOKEN"

SIZE=$(wc -c < "$APK" | tr -d ' ')
MB=$(awk "BEGIN{printf \"%.1f\", $SIZE/1048576}")
echo "APK: $APK  (${MB} MB)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ---------- 1. 组装请求体 ----------
cat > "$WORK/body.md" <<'BODY_EOF'
## 打卡 v4.1

Android 地图打卡 App：在卫星影像上标记去过的地方、拍照记录、按行政区分层浏览足迹。

### 主要功能
- **卫星底图**：Esri World Imagery，按区域自动限制最大缩放，不会放大到灰块
- **分层足迹**：全国 → 省 → 市 → 区县 → 具体点位，每层只显示去过的
- **行政边界**：省界 / 市界 / 区县界随层级自动切换
- **数字聚合气泡**：缩小时相近的点合并成带数字的气泡（自己实现的屏幕空间聚类）
- **拍照**：调系统相机，原图不压缩、无水印，EXIF 补写 GPS；长按拍照键可选第三方相机 App
- **标记自定义**：10 种形状 / 12 种颜色 / 20 个 emoji / 自定义图片
- **分享卡片**：三种版式，默认位置脱敏
- **导航分享**：一键发到高德 / 百度 / 腾讯 / Google，按目标 App 做 WGS/GCJ/BD 坐标转换
- **足迹轨迹**：前台服务记录，导出 GPX
- **成就系统**：20 个成就
- **备份恢复**：导出 zip

### 安装
1. 下载下面的 `daka-v4.1.apk`
2. 需要 **Android 8.0+**（minSdk 26）
3. 小米 / 红米提示「已禁止安装」时：`设置 → 更多设置 → 开发者选项` 打开「USB 安装」，
   或用文件管理器点开 APK 选「继续安装」
4. 首次打开会问定位权限，选「仅在使用中允许」

### 已知限制
- 卫星影像多数地区到 18 级（约 0.3~0.6 米/像素），偏远地区只到 17 级
- 底图来自 Esri，官方条款要求配合 Esri 软件或 ArcGIS Online 订阅使用。
  个人自用问题不大，上架应用商店前需换源
- 导航跳转未在装了第三方地图 App 的设备上验证过（坐标转换逻辑有单测覆盖）
- 地图上「市内看区县」这一层的气泡位置不准，但侧边列表 + 区县点列表完全可用

详细说明见仓库里的 `README.md` 与 `09-数字聚合与区县子菜单-实现记录.md`。
BODY_EOF

if command -v node >/dev/null 2>&1; then
  node -e 'const fs=require("fs");const b=fs.readFileSync(process.argv[1],"utf8");fs.writeFileSync(process.argv[3],JSON.stringify({tag_name:process.argv[2],name:"打卡 "+process.argv[2],body:b,draft:false,prerelease:false}));' "$WORK/body.md" "$TAG" "$WORK/req.json"
else
  BODY_ESC=$(sed ':a;N;$!ba;s/\n/\\n/g' "$WORK/body.md")
  printf '{"tag_name":"%s","name":"打卡 %s","body":"%s","draft":false,"prerelease":false}' "$TAG" "$TAG" "$BODY_ESC" > "$WORK/req.json"
fi

# ---------- 2. 创建 release ----------
echo "== 1/3 创建 Release $TAG =="
RELEASE_URL="$API/repos/$REPO_SLUG/releases"
curl -s -X POST -H "$AUTH" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" --data-binary @"$WORK/req.json" "$RELEASE_URL" > "$WORK/resp.json"

RELEASE_ID=$(sed -n 's/.*"id":[[:space:]]*\([0-9]*\).*/\1/p' "$WORK/resp.json" | head -1)
PAGE_URL=$(sed -n 's/.*"html_url":[[:space:]]*"\([^"]*\)".*/\1/p' "$WORK/resp.json" | head -1)

if [ -z "$RELEASE_ID" ]; then
  echo "   创建失败，GitHub 返回：" >&2
  head -c 600 "$WORK/resp.json" >&2
  echo >&2
  echo "   提示：若 $TAG 已存在，换一个 tag 名（如 v4.1.1）" >&2
  exit 1
fi
echo "   Release ID: $RELEASE_ID"
echo "   页面: $PAGE_URL"

# ---------- 3. 上传 APK ----------
echo "== 2/3 上传 APK（${MB} MB，请稍候）=="
UP_NAME="daka-${TAG}.apk"
ASSET_URL_FULL="$UPLOAD_API/repos/$REPO_SLUG/releases/$RELEASE_ID/assets?name=$UP_NAME"
curl -s -X POST -H "$AUTH" -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$APK" "$ASSET_URL_FULL" > "$WORK/up.json"

ASSET_URL=$(sed -n 's/.*"browser_download_url":[[:space:]]*"\([^"]*\)".*/\1/p' "$WORK/up.json" | head -1)
STATE=$(sed -n 's/.*"state":[[:space:]]*"\([^"]*\)".*/\1/p' "$WORK/up.json" | head -1)

echo "== 3/3 结果 =="
if [ -n "$ASSET_URL" ]; then
  echo "   上传成功（state=${STATE:-unknown}）"
  echo
  echo "Release 页面：$PAGE_URL"
  echo "APK 直链：   $ASSET_URL"
else
  echo "   上传结果异常：" >&2
  head -c 400 "$WORK/up.json" >&2
  echo >&2
  echo "   Release 已建好，可去页面手动上传：$PAGE_URL" >&2
  exit 1
fi
