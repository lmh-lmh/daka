# 发布到 GitHub · 操作卡片

> 状态：**本地仓库已就绪**，只差在 GitHub 网页建一个空仓库 + 推送
> 目标地址：https://github.com/lmh-lmh/daka

---

## 你需要做的（浏览器里，约 30 秒）

1. 打开 https://github.com/new

2. 填这些：

   | 字段 | 填什么 |
   |---|---|
   | **Repository name** | `daka` |
   | **Description** | `Android 地图打卡 App：分层足迹、卫星底图、原图拍照、数字聚合` |
   | **Public / Private** | 选 **Public** |
   | **Add a README file** | **不要勾**（重要：勾了会和本地冲突） |
   | **Add .gitignore** | **None** |
   | **Choose a license** | **None** |

3. 点绿色按钮 **Create repository**

4. 建好后页面会显示一堆命令，**不用管**，直接回来告诉我「建好了」

---

## 建好之后我执行

```bash
cd "E:/ai/deepseek/地图打卡照相APP-需求与方案"
git push -u origin main
```

会弹一个窗口让你授权 GitHub（浏览器登录状态会带过去，点一下就好）。

---

## 如果不想推送时弹窗

在网页建仓库的同时，可以顺手生成一个 token：
https://github.com/settings/tokens → Generate new token (classic) → 勾 `repo` → 生成

然后把 token 写进文件（**不要贴进对话**）：

```bash
printf 'ghp_xxxx\n' > ~/.daka-github-token
chmod 600 ~/.daka-github-token
```

我就能全自动完成，不用你点授权窗口。

---

## 仓库现状（已就绪）

| 项 | 值 |
|---|---|
| 文件数 | 153 |
| 体积 | 63 MB |
| 分支 | `main` |
| 最新提交 | `e3c8c8c` |
| remote | `https://github.com/lmh-lmh/daka.git` |

### 里面有什么

- `新工程/` — 完整 Android 源码（Kotlin，66 个单测）
- `新工程/app/src/main/assets/admin/` — 行政区划数据（省/市/区县中心点与边界）
- `tools/` — 行政区划数据构建脚本、推送脚本
- `research/` — 底图可用性与最大缩放级别的探测数据
- `00~09-*.md` — 需求、技术方案、开发计划、真机验证记录
- `打卡-v4.1.apk` — 安装包

### 排除了什么

- `新工程/build/`、`新工程/app/build/` — 编译中间产物（约 1 GB）
- `新工程/验证/*.png` — 70 多张 7~8 MB 的真机调试截图（约 740 MB）
- `打卡-v1.0.apk` — 旧版安装包

### 已做安全检查

- 扫描 token / 密钥 / 私钥样式字符串 → 无命中
- 扫描手机号 / 邮箱 / 身份证样式 → 无命中
- 扫描硬编码本机绝对路径 → 只剩 `build.sh` 的注释说明，代码已改为环境变量
