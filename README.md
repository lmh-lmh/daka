# 打卡 · Daka

一个 Android 地图打卡 App：在卫星影像地图上标记去过的地方、拍照记录、按行政区分层浏览足迹。

**核心理念**：记录生活，不是干活。原图直出、无水印、按行政区分层看自己走过多远。

---

## 它长什么样

| 功能 | 说明 |
|---|---|
| **卫星底图** | Esri World Imagery，全球覆盖，带缩放保护（该区域影像到几级就停在哪，不会给你看灰块） |
| **分层足迹** | 全国看省 → 点省看市 → 点市看区县 → 点区县看具体点位。每一层只显示"去过的"，没去过的不占地方 |
| **行政边界** | 省界 / 市界 / 区县界随层级自动切换，暖黄色描边 |
| **数字聚合** | 缩小时相近的点自动合并成带数字的气泡（自己算的屏幕空间聚类，不依赖 MapLibre 内置聚类） |
| **拍照** | 调系统相机，原图不压缩、无水印，EXIF 补写 GPS。长按拍照键可选第三方相机 App |
| **标记自定义** | 10 种形状 / 12 种颜色 / 20 个 emoji / 自定义图片 / 大小可调 |
| **分享卡片** | 三种版式（深色经典 / 拍立得 / 旅行条带），默认位置脱敏 |
| **导航分享** | 一键发到高德/百度/腾讯/Google，按目标 App 做 WGS/GCJ/BD 坐标转换 |
| **足迹轨迹** | 前台服务记录轨迹，导出 GPX |
| **成就系统** | 20 个成就，数量/地域/深度/坚持四类 |
| **备份恢复** | 导出 zip（数据库 + 缩略图 + 自定义图标） |

---

## 安装

从 [Releases](../../releases) 下载最新的 `daka-x.y.apk`（约 55 MB）。

- 需要 **Android 8.0+**（minSdk 26）
- 小米/红米如果提示「已禁止安装」，去 `设置 → 更多设置 → 开发者选项` 打开「USB 安装」，或用文件管理器点开 APK 选「继续安装」
- 首次打开会问定位权限，选「仅在使用中允许」

---

## 从源码编译

```bash
# 需要 JDK 17、Android SDK（compileSdk 34）、Gradle 8.13
export TOOLCHAIN=/path/to/toolchain   # 含 jdk17/ android-sdk/ gradle-8.13/
bash build.sh assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 上如果工程路径含中文，先给工程建一个 ASCII 路径的 junction 再编译，并把 `GRADLE_USER_HOME` 指到可写目录 —— `build.sh` 顶部注释里写了原因。

### 跑单元测试

```bash
bash test.sh
# 期望：OK (66 tests)
```

> `test.sh` 是手写的（不走 `gradle testDebugUnitTest`）。Gradle 的 test worker JVM 在中文路径下会
> `ClassNotFoundException`，所以它手工组装 classpath 并把产物复制到 ASCII 路径再跑。

---

## 技术栈与几个关键决策

| 选择 | 为什么 |
|---|---|
| **Kotlin 2.0.21 + AGP 8.7.3** | — |
| **MapLibre 11.13.5** | Mapbox GL 的开源分支，BSD-2，可商用 |
| **手写 SQLiteOpenHelper，不用 Room** | 避开 KSP 版本耦合；表结构简单 |
| **不用 Coil** | 它的磁盘缓存只对网络图有效，本地图用不上 |
| **不用 CameraX** | 原厂相机的 HDR/夜景/多帧合成是厂商调了几年的，追不上 |
| **不用 Compose** | 界面不复杂，View 体系够用且编译更快 |

### 几个踩过的坑（代码里有详细注释）

1. **MapLibre 的 zoom 语义和瓦片层级差 1 级**。`tileSize: 256` 的 raster source 恒定请求 `zoom+1` 级瓦片。宜春的影像只到 z18，所以地图 `maxZoom` 必须设 17。见 `ZoomGuard.TILE_ZOOM_OFFSET`。

2. **MapLibre 内置聚类在这个工程里不工作**。推 1002 个点，`point_count` 永远是 0，渲染层 feature 数是 0。所以聚合是自己在 `SpotClusterer` 里用墨卡托像素坐标算的。

3. **不能用 `map.projection.toScreenLocation` 反推**。它和 `cameraPosition.zoom` 的坐标系不一致（本机 density=2.25 时差 2.36 级），算出来的屏幕坐标会偏到屏幕外。

4. **国内导航 App 的坐标基准不同**。高德/腾讯用 GCJ-02，百度用 BD-09，直接丢 WGS-84 会偏 300~700 米。见 `NavShare.Coord`。

5. **`ACTION_IMAGE_CAPTURE` 不要声明 CAMERA 权限**。声明了但没授权会抛 `SecurityException`（官方 javadoc 明确写了）。

---

## 目录结构

```
新工程/app/src/main/
├── kotlin/com/daka/app/
│   ├── MainActivity.kt            主界面：地图、分层、聚类、卡片
│   ├── SpotEditActivity.kt        编辑标记样式
│   ├── SpotDetailActivity.kt      详情页 + 照片墙 + 生成卡片
│   ├── RegionSpotsActivity.kt     区县内的点列表
│   ├── AchievementsActivity.kt    成就
│   ├── core/
│   │   ├── map/      地图、分层、聚类、边界、导航分享
│   │   ├── db/       数据库与 DAO
│   │   ├── capture/  系统相机、EXIF
│   │   ├── photo/    照片入库
│   │   ├── share/    分享卡片
│   │   ├── track/    轨迹与 GPX
│   │   ├── backup/   备份恢复
│   │   └── achieve/  成就计算
│   └── res/          布局与资源
└── assets/admin/     行政区划数据（省/市/区县的中心点与边界，约 2.6 MB）

tools/                构建行政区划数据的脚本（Python）
```

行政区划数据来自阿里云 DataV `areas_v3`，构建期转成 WGS-84 并简化，产物进 `assets/admin/`。
原始数据是 GCJ-02，直接用会偏 2~3 公里。

---

## 已知限制

| # | 限制 |
|---|---|
| 1 | **底图条款**：Esri World Imagery 的官方 FAQ 要求配合 Esri 软件或 ArcGIS Online 订阅，不允许第三方 App 直接拉瓦片，没有个人使用豁免。个人自用风险不大，**上架应用商店前必须换源** |
| 2 | **卫星影像精度**：多数地区到 z18（约 0.3~0.6 米/像素），少数偏远地区只到 z17 |
| 3 | **L2 层（市内看区县）地图上的气泡位置不准**。不影响使用 —— 这一级走侧边列表 + 点列表完全可用 |
| 4 | **导航跳转未在装了第三方地图的设备上验证过**。坐标转换有单测覆盖，但 URI 方案只在没有地图 App 的设备上测到兜底分支 |
| 5 | 行政区划归类是**多边形包含 + 最近中心**混合，边界上极少数点可能归到邻省 |

---

## 许可证

代码 MIT。**但行政区划数据（`assets/admin/`）来自阿里云 DataV，请遵守其条款**；
底图来自 Esri，见上面的限制说明。

---

## 截图

见 `docs/` 目录（真机截图与验证记录在开发文档里，未随仓库发布以控制体积）。
