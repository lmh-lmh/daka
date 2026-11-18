package com.daka.app

import com.daka.app.core.capture.SystemCamera
import com.daka.app.core.db.Spot
import com.daka.app.core.db.TrackDao
import com.daka.app.core.map.ZoomGuard
import com.daka.app.core.achieve.Achievements
import com.daka.app.core.map.AdminTree
import com.daka.app.core.map.NavShare
import com.daka.app.core.map.SpotClusterer
import com.daka.app.core.map.MapLevel
import com.daka.app.core.share.ShareCard
import com.daka.app.core.track.GpxExporter
import com.daka.app.core.track.TrackGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单元测试。跑在 JVM 上，不需要真机。
 *
 * 只测"不碰 Android 框架"的部分：距离、日期、文件名清洗、占位图判据。
 * 这些是最容易被边界值坑到、又最容易在没有真机时先验一遍的东西。
 * 需要 Context 的逻辑（SQLite、MediaStore、Bitmap）不在这里测——那必须上真机。
 */
class CoreLogicTest {

    // ---------------- 距离 ----------------

    @Test
    fun distance_同一地点为零() {
        val d = TrackGeo.distanceM(114.4, 27.8, 114.4, 27.8)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun distance_纬度一度约111公里() {
        // 1 度纬度在全球都约 111.19 km（子午线弧长），容差 1%
        val d = TrackGeo.distanceM(115.0, 27.0, 115.0, 28.0)
        assertTrue("实际 $d", d in 110_000.0..112_500.0)
    }

    @Test
    fun distance_经度一度随纬度变短() {
        // 赤道 1 度经度 ≈ 111.3 km，北纬 60 度 ≈ 55.7 km（cos60 = 0.5）
        val equator = TrackGeo.distanceM(0.0, 0.0, 1.0, 0.0)
        val at60 = TrackGeo.distanceM(0.0, 60.0, 1.0, 60.0)
        assertTrue("赤道 $equator", equator in 111_000.0..111_500.0)
        assertTrue("北纬60 $at60", at60 in 55_000.0..56_000.0)
    }

    @Test
    fun distance_跨经度180不倒转() {
        // 179.9 与 -179.9 实际只隔 0.2 度，不能算成 359.8 度
        val d = TrackGeo.distanceM(179.9, 0.0, -179.9, 0.0)
        assertTrue("实际 $d", d < 25_000.0)
    }

    @Test
    fun prettyMeters_千米和米的分界() {
        assertEquals("350 米", TrackGeo.prettyMeters(350.4))
        assertEquals("1.2 公里", TrackGeo.prettyMeters(1234.0))
        assertEquals("0 米", TrackGeo.prettyMeters(0.0))
    }

    // ---------------- 轨迹抽稀与长度 ----------------

    private fun pt(lon: Double, lat: Double, t: Long = 0L) =
        TrackDao.Point(lon, lat, 5f, t)

    @Test
    fun lineOf_少于两个点画不成线() {
        val empty = TrackGeo.lineOf(emptyList())
        assertTrue(TrackGeo.isEmpty(empty))
        val one = TrackGeo.lineOf(listOf(pt(114.4, 27.8)))
        assertTrue(TrackGeo.isEmpty(one))
    }

    @Test
    fun lineOf_静止抖动的点被抽稀() {
        // 站在原地不动，GPS 在 1 米范围内来回跳（不累积位移），
        // 100 个点应该被 8 米阈值全部滤掉，只剩起点 -> 抽稀后不足 2 点 -> 空集合
        val jitter = (0 until 100).map { i ->
            val dx = if (i % 2 == 0) 0.000005 else -0.000005
            val dy = if (i % 3 == 0) 0.000004 else -0.000004
            pt(114.4 + dx, 27.8 + dy)
        }
        assertTrue(TrackGeo.isEmpty(TrackGeo.lineOf(jitter)))
    }

    @Test
    fun lineOf_持续朝一个方向走即使慢也要画出来() {
        // 每步只走 1 米，但一直朝东走 100 步（累计约 100 米），
        // 这是"真的在走"，不能因为单步小于 8 米就被整条丢掉
        val walk = (0 until 100).map { i -> pt(114.4 + i * 0.00001, 27.8) }
        val fc = TrackGeo.lineOf(walk)
        assertFalse(TrackGeo.isEmpty(fc))
    }

    @Test
    fun lineOf_真实走动保留轮廓() {
        // 沿纬度走约 1 公里，每 100 米一个点
        val walk = (0..10).map { i -> pt(114.4, 27.8 + i * 0.0009) }
        val fc = TrackGeo.lineOf(walk)
        assertFalse(TrackGeo.isEmpty(fc))
        assertEquals(1, fc.features()?.size ?: 0)
    }

    @Test
    fun totalMeters_静止为零() {
        val jitter = (0 until 50).map { i -> pt(114.4 + i * 0.0000001, 27.8) }
        assertEquals(0.0, TrackGeo.totalMeters(jitter), 1.0)
    }

    @Test
    fun totalMeters_一公里走路() {
        // 0.009 度纬度 ≈ 1.0 km
        val walk = (0..10).map { i -> pt(114.4, 27.8 + i * 0.0009) }
        val m = TrackGeo.totalMeters(walk)
        assertTrue("实际 $m", m in 900.0..1100.0)
    }

    @Test
    fun isEmpty_null也算空() {
        assertTrue(TrackGeo.isEmpty(null))
    }

    // ---------------- 日期 ----------------

    @Test
    fun dayRange_一天正好86400000毫秒() {
        val r = TrackDao.dayRange("2026-11-13")
        assertTrue(r != null)
        assertEquals(86_400_000L, r!!.second - r.first)
    }

    @Test
    fun dayRange_格式不对返回null() {
        assertEquals(null, TrackDao.dayRange("2026/11/13"))
        assertEquals(null, TrackDao.dayRange(""))
        assertEquals(null, TrackDao.dayRange("不是日期"))
    }

    @Test
    fun dayOf与dayRange自洽() {
        val day = "2026-11-13"
        val r = TrackDao.dayRange(day)!!
        // 当天中午落在这个区间里
        val noon = r.first + 12L * 3600 * 1000
        assertEquals(day, TrackDao.dayOf(noon))
    }

    @Test
    fun prettyDay_输出中文() {
        val s = TrackDao.prettyDay("2026-11-13")
        assertTrue("实际 $s", s.contains("11月13日"))
    }

    // ---------------- 文件名清洗 ----------------

    @Test
    fun sanitize_去掉路径分隔符() {
        assertEquals("a_b_c", SystemCamera.sanitize("a/b\\c"))
    }

    @Test
    fun sanitize_非法字符换成下划线() {
        val out = SystemCamera.sanitize("a:b*c?d\"e<f>g|h")
        for (ch in listOf(':', '*', '?', '"', '<', '>', '|')) {
            assertFalse("还留着 $ch", out.contains(ch))
        }
        // 实现是把非法字符替换成下划线，不是删掉——替换更能看出原名字被改过
        assertEquals("a_b_c_d_e_f_g_h", out)
    }

    @Test
    fun sanitize_空名给兜底() {
        assertEquals("未命名", SystemCamera.sanitize(""))
        assertEquals("未命名", SystemCamera.sanitize("   "))
        assertEquals("未命名", SystemCamera.sanitize("..."))
    }

    @Test
    fun sanitize_超长截断() {
        val long = "啊".repeat(100)
        val out = SystemCamera.sanitize(long)
        assertTrue("长度 ${out.length}", out.length <= 40)
    }

    @Test
    fun sanitize_中文正常保留() {
        assertEquals("宜春明月山", SystemCamera.sanitize("宜春明月山"))
        assertEquals("宜春明月山", SystemCamera.sanitize("  宜春明月山  "))
    }

    @Test
    fun fileName_格式正确() {
        // IMG_20261113_093500.jpg
        val n = SystemCamera.fileName(System.currentTimeMillis())
        assertTrue(n, n.startsWith("IMG_"))
        assertTrue(n, n.endsWith(".jpg"))
        assertEquals(19 + 4, n.length)
    }

    // ---------------- 底图占位图判据 ----------------

    @Test
    fun placeholder_2521字节判为占位() {
        assertTrue(ZoomGuard.looksLikePlaceholder(2521))
        assertTrue(ZoomGuard.looksLikePlaceholder(2400))
        assertTrue(ZoomGuard.looksLikePlaceholder(2700))
    }

    @Test
    fun placeholder_真图不判占位() {
        assertFalse(ZoomGuard.looksLikePlaceholder(15000))
        assertFalse(ZoomGuard.looksLikePlaceholder(5000))
        assertFalse(ZoomGuard.looksLikePlaceholder(2399))
        assertFalse(ZoomGuard.looksLikePlaceholder(2701))
    }

    @Test
    fun maxZoomFor_宜春是18() {
        // 实测宜春 z19 是灰底占位，最高只到 18
        assertEquals(18, ZoomGuard.maxZoomFor(114.4167, 27.8000))
    }

    @Test
    fun maxZoomFor_北京是19() {
        // 实测北京能到 19；广州之类没测过的地方按保底 18 给
        assertEquals(19, ZoomGuard.maxZoomFor(116.40, 39.90))
    }

    @Test
    fun maxZoomFor_纽约是20() {
        assertEquals(20, ZoomGuard.maxZoomFor(-74.0060, 40.7128))
    }

    @Test
    fun maxZoomFor_没测过的地方回保底18() {
        // 广州没在我的实测名单里，不许瞎猜，一律给保底 18
        assertEquals(18, ZoomGuard.maxZoomFor(113.2644, 23.1291))
    }

    @Test
    fun maxZoomFor_默认不低于18() {
        // 没收录的地方一律按全球保底 18 给，不能比 18 更低
        assertTrue(ZoomGuard.maxZoomFor(-43.17, -22.90) >= 18)
        assertTrue(ZoomGuard.maxZoomFor(0.0, 0.0) >= 18)
    }

    @Test
    fun preferenceFor_比瓦片上限低一级() {
        // 实测（2026-11-13 真机 logcat）：地图 zoom 恒定请求 zoom+1 层瓦片。
        // 所以宜春瓦片到 z18 时，地图 zoom 必须压在 17，否则一定请求 z19 占位图。
        assertEquals(17.0, ZoomGuard.preferenceFor(114.4167, 27.8000), 0.001)
        // 北京瓦片到 19 -> 地图 18；纽约瓦片到 20 -> 地图 19
        assertEquals(18.0, ZoomGuard.preferenceFor(116.40, 39.90), 0.001)
        assertEquals(19.0, ZoomGuard.preferenceFor(-74.0060, 40.7128), 0.001)
        // 没测过的地方：瓦片保底 18 -> 地图 17
        assertEquals(17.0, ZoomGuard.preferenceFor(113.2644, 23.1291), 0.001)
    }

    @Test
    fun tileZoomOffsetIsOne() {
        // 这个常量是真机实测出来的，改它等于改所有区域的缩放上限，值得钉住
        assertEquals(1, ZoomGuard.TILE_ZOOM_OFFSET)
    }

    @Test
    fun regionNameFor_命中江西六市() {
        assertEquals("宜春", ZoomGuard.regionNameFor(114.4167, 27.8000))
        assertEquals("赣州", ZoomGuard.regionNameFor(114.9333, 25.8333))
        assertEquals("九江", ZoomGuard.regionNameFor(115.9833, 29.7333))
        assertEquals("上饶", ZoomGuard.regionNameFor(117.9667, 28.4500))
        assertEquals("吉安", ZoomGuard.regionNameFor(114.9833, 27.1167))
        assertEquals("抚州", ZoomGuard.regionNameFor(116.3500, 27.9500))
    }

    @Test
    fun regionNameFor_远处不命中() {
        // 太平洋中间、西伯利亚，都不在任何已知区域里
        assertEquals(null, ZoomGuard.regionNameFor(-140.0, 0.0))
        assertEquals(null, ZoomGuard.regionNameFor(100.0, 65.0))
    }

    @Test
    fun regionNameFor_高精度城市也认识() {
        assertEquals("北京", ZoomGuard.regionNameFor(116.40, 39.90))
        assertEquals("巴黎", ZoomGuard.regionNameFor(2.35, 48.85))
    }

    // ---------------- GPX 导出 ----------------

    @Test
    fun gpx_结构完整且点都在() {
        val pts = listOf(
            TrackDao.Point(114.4, 27.8, 5f, 1_700_000_000_000L),
            TrackDao.Point(114.401, 27.801, 8f, 1_700_000_060_000L),
        )
        val xml = GpxExporter.buildXml("2026-11-13", pts)
        assertTrue("缺 gpx 头", xml.contains("""<gpx version="1.1""""))
        assertTrue("缺 trkseg", xml.contains("<trkseg>"))
        assertEquals("trkpt 数量不对", 2, Regex("<trkpt ").findAll(xml).count())
        assertTrue("纬度没写进去", xml.contains("""lat="27.8000000""""))
        assertTrue("经度没写进去", xml.contains("""lon="114.4000000""""))
        // 时间是 UTC 的 ISO-8601，必须带 Z
        assertTrue(xml.contains("Z</time>"))
    }

    @Test
    fun gpx_空点集也能生成合法头() {
        // 正常流程不会走到这里（上层拦了），但函数本身不该崩
        val xml = GpxExporter.buildXml("2026-11-13", emptyList())
        assertTrue(xml.contains("</gpx>"))
        assertEquals(0, Regex("<trkpt ").findAll(xml).count())
    }

    @Test
    fun gpx_名字里的特殊字符被转义() {
        val xml = GpxExporter.buildXml("a&b<c>\"d\"", emptyList())
        assertFalse("& 没转义", xml.contains("a&b"))
        assertTrue(xml.contains("a&amp;b"))
    }

    // ---------------- 卡片脱敏 ----------------

    @Test
    fun 卡片位置_默认只到两位小数() {
        // 分享卡片默认不能暴露精确坐标。两位小数约 1 公里，够表达"在哪座城市附近"。
        val spot = com.daka.app.core.db.Spot(
            id = 1, lon = 114.416789, lat = 27.800123, name = "测试点",
            createdAt = 1_700_000_000_000L,
        )
        val loc = ShareCard.locText(spot, showCoord = false)
        assertEquals("27.80, 114.42 附近", loc)
        // 精确到六位小数的原值一个字都不能出现在脱敏结果里
        assertFalse(loc.contains("114.416789"))
        assertFalse(loc.contains("27.800123"))
    }

    @Test
    fun 卡片位置_打开后才写精确值() {
        val spot = com.daka.app.core.db.Spot(
            id = 1, lon = 114.416789, lat = 27.800123, name = "测试点",
            createdAt = 1_700_000_000_000L,
        )
        val loc = ShareCard.locText(spot, showCoord = true)
        assertEquals("27.800123, 114.416789", loc)
    }

    @Test
    fun 卡片模板_三套且都有中文名() {
        val ts = ShareCard.Template.values()
        assertEquals(3, ts.size)
        for (t in ts) {
            assertTrue("模板 ${t.name} 没写中文名", t.label.isNotBlank())
        }
    }

    // ---------------- 分层视图：层级换算与坐标 ----------------

    @Test
    fun levelForZoom_四层边界() {
        // 阈值：<6 全国 / 6~9 省内 / 9~12 市内 / >=12 打卡点
        assertEquals(MapLevel.L0_PROVINCE, MapLevel.levelForZoom(3.0))
        assertEquals(MapLevel.L0_PROVINCE, MapLevel.levelForZoom(5.9))
        assertEquals(MapLevel.L1_CITY, MapLevel.levelForZoom(6.0))
        assertEquals(MapLevel.L1_CITY, MapLevel.levelForZoom(8.9))
        assertEquals(MapLevel.L2_DISTRICT, MapLevel.levelForZoom(9.0))
        // L3 起点从 12 降到 11：z11 时点已经足够稀疏，用真实标记比气泡更可靠
        assertEquals(MapLevel.L2_DISTRICT, MapLevel.levelForZoom(10.9))
        assertEquals(MapLevel.L3_SPOT, MapLevel.levelForZoom(11.0))
        assertEquals(MapLevel.L3_SPOT, MapLevel.levelForZoom(12.0))
        assertEquals(MapLevel.L3_SPOT, MapLevel.levelForZoom(17.0))
    }

    @Test
    fun zoomForLevel_逐层放大() {
        // 下钻时每层都要比上一层更近，否则点进去看不出变化
        val z0 = MapLevel.zoomForLevel(MapLevel.L0_PROVINCE)
        val z1 = MapLevel.zoomForLevel(MapLevel.L1_CITY)
        val z2 = MapLevel.zoomForLevel(MapLevel.L2_DISTRICT)
        val z3 = MapLevel.zoomForLevel(MapLevel.L3_SPOT)
        assertTrue("$z0 -> $z1", z0 < z1)
        assertTrue("$z1 -> $z2", z1 < z2)
        assertTrue("$z2 -> $z3", z2 < z3)
        // 而且每层的目标 zoom 必须落在它自己那一层里，否则点完立刻又跳层
        assertEquals(MapLevel.L0_PROVINCE, MapLevel.levelForZoom(z0))
        assertEquals(MapLevel.L1_CITY, MapLevel.levelForZoom(z1))
        assertEquals(MapLevel.L2_DISTRICT, MapLevel.levelForZoom(z2))
        assertEquals(MapLevel.L3_SPOT, MapLevel.levelForZoom(z3))
    }

    @Test
    fun adminTree_距离算法与ZoomGuard一致() {
        // 两处各有一份 Haversine，数值必须一致，否则"归到哪个省"和"能放到几级"会打架
        val a = AdminTree.distanceKm(114.4, 27.8, 115.9, 28.7)
        val b = ZoomGuard.distanceKm(114.4, 27.8, 115.9, 28.7)
        assertEquals(b, a, 1e-9)
    }

    @Test
    fun adminTree_境外网格key稳定可逆() {
        val size = 10.0
        val k1 = AdminTree.gridKey(114.4, 27.8, size)
        val k2 = AdminTree.gridKey(114.5, 27.9, size)
        // 同一格里两个点应该给同一个 key
        assertEquals(k1, k2)
        // 相邻格给不同 key
        val k3 = AdminTree.gridKey(125.0, 27.8, size)
        assertTrue(k1 != k3)
        // 从 key 解回来的中心点应落在该格内
        val (cx, cy) = AdminTree.gridCenter(k1, size)
        assertTrue("cx=$cx", cx >= 110.0 && cx <= 120.0)
        assertTrue("cy=$cy", cy >= 20.0 && cy <= 30.0)
    }

    @Test
    fun adminTree_网格随层级变细() {
        // L0 粗、L1 中、L2 细，否则下钻时网格不变，看着没反应
        val s0 = AdminTree.gridSizeFor(MapLevel.L0_PROVINCE)
        val s1 = AdminTree.gridSizeFor(MapLevel.L1_CITY)
        val s2 = AdminTree.gridSizeFor(MapLevel.L2_DISTRICT)
        assertTrue("$s0 > $s1", s0 > s1)
        assertTrue("$s1 > $s2", s1 > s2)
    }

    // ---------------- 成就系统 ----------------

    private fun dayMillis(y: Int, m: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(y, m - 1, d, 12, 0, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun achievements_连续天数_空列表为零() {
        assertEquals(0, Achievements.longestStreak(emptyList()))
    }

    @Test
    fun achievements_连续天数_单日为一() {
        assertEquals(1, Achievements.longestStreak(listOf(dayMillis(2026, 11, 13))))
    }

    @Test
    fun achievements_连续天数_三天连续() {
        val days = listOf(
            dayMillis(2026, 11, 11),
            dayMillis(2026, 11, 12),
            dayMillis(2026, 11, 13),
        )
        assertEquals(3, Achievements.longestStreak(days))
    }

    @Test
    fun achievements_连续天数_断开后重新计数() {
        // 11/11, 11/12 连续两天；隔一天；11/14, 11/15, 11/16 连续三天 -> 最长 3
        val days = listOf(
            dayMillis(2026, 11, 11), dayMillis(2026, 11, 12),
            dayMillis(2026, 11, 14), dayMillis(2026, 11, 15), dayMillis(2026, 11, 16),
        )
        assertEquals(3, Achievements.longestStreak(days))
    }

    @Test
    fun achievements_连续天数_同一天重复只算一天() {
        val m = dayMillis(2026, 11, 13)
        assertEquals(1, Achievements.longestStreak(listOf(m, m, m, m)))
    }

    @Test
    fun achievements_连续天数_跨月也连续() {
        // 11/30 和 12/1 是连续的，不能因为"日"变小就断开
        val days = listOf(dayMillis(2026, 11, 30), dayMillis(2026, 12, 1))
        assertEquals(2, Achievements.longestStreak(days))
    }

    @Test
    fun achievements_定义完整且id唯一() {
        val ids = Achievements.ALL.map { it.id }
        assertEquals("id 有重复：$ids", ids.size, ids.toSet().size)
        for (d in Achievements.ALL) {
            assertTrue("${d.id} 标题为空", d.title.isNotBlank())
            assertTrue("${d.id} 阈值为 0", d.target > 0)
            assertTrue("${d.id} 图标为空", d.icon.isNotBlank())
        }
    }

    @Test
    fun achievements_进度封顶与解锁判定() {
        val stats = Achievements.Stats(
            spotCount = 12, photoCount = 30, provinceCount = 2, cityCount = 3,
            maxProvinceSpotCount = 10, distinctDays = 4, longestStreak = 3,
            maxSpotsInOneDay = 5,
        )
        val states = Achievements.evaluate(stats)
        // 打卡 12 个 -> first(1) 和 c10(10) 解锁，c50(50) 没解锁
        val byId = states.associateBy { it.def.id }
        assertTrue(byId["first"]!!.unlocked)
        assertTrue(byId["c10"]!!.unlocked)
        assertFalse(byId["c50"]!!.unlocked)
        // 进度值就是实际统计值，不封顶（UI 里再 coerce）
        assertEquals(12, byId["c50"]!!.progress)
        // ratio 必须封顶在 1.0
        assertTrue(byId["c50"]!!.ratio <= 1f)
        assertTrue(byId["first"]!!.ratio == 1f)
        // 连续 3 天 -> s3 解锁，s7 未解锁
        assertTrue(byId["s3"]!!.unlocked)
        assertFalse(byId["s7"]!!.unlocked)
    }

    // ---------------- 导航分享的坐标转换 ----------------

    @Test
    fun coord_天安门WGS转GCJ在北京的已知偏移() {
        // 天安门 WGS-84 约 (116.3912, 39.9075)，正算成 GCJ-02 应约 (116.3974, 39.9089)
        // 这是公开可查的标准值，用来钉住转换公式没写错
        val (gLon, gLat) = NavShare.Coord.wgs84ToGcj02(116.3912, 39.9075)
        assertEquals(116.3974, gLon, 0.002)
        assertEquals(39.9089, gLat, 0.002)
    }

    @Test
    fun coord_转换后确实有偏移且量级合理() {
        // 实测过：偏移方向因地区而异，不能断言"向东北偏"。
        // 宜春这个点经度增大、纬度反而减小（28.2329 → 28.2298）。
        // 所以只断言"有偏移"和"量级在几百米内"。
        val lon = 115.4068
        val lat = 28.2329
        val (gLon, gLat) = NavShare.Coord.wgs84ToGcj02(lon, lat)
        val dLon = Math.abs(gLon - lon)
        val dLat = Math.abs(gLat - lat)
        assertTrue("应该有偏移: dLon=$dLon dLat=$dLat", dLon > 1e-6 || dLat > 1e-6)
        // 300~700 米的典型偏移，换算成度约 0.003~0.007
        assertTrue("偏移量级不对: dLon=$dLon", dLon in 1e-4..0.01)
        assertTrue("偏移量级不对: dLat=$dLat", dLat in 1e-4..0.01)
    }

    @Test
    fun coord_GCJ转回WGS能还原() {
        // 往返一致是这套算法最基本的要求，差超过 1e-5 度（约 1 米）就算错
        val lon = 115.4068
        val lat = 28.2329
        val (gLon, gLat) = NavShare.Coord.wgs84ToGcj02(lon, lat)
        val (wLon, wLat) = NavShare.Coord.gcj02ToWgs84(gLon, gLat)
        assertEquals(lon, wLon, 1e-5)
        assertEquals(lat, wLat, 1e-5)
    }

    @Test
    fun coord_境外不做偏移() {
        // 东京(139.69, 35.69) 在中国范围外，公式不适用，必须原样返回
        val (lon, lat) = NavShare.Coord.wgs84ToGcj02(139.69, 35.69)
        assertEquals(139.69, lon, 1e-9)
        assertEquals(35.69, lat, 1e-9)
    }

    @Test
    fun coord_BD09在GCJ基础上继续偏移() {
        // BD-09 是 GCJ 再加一层，所以离 WGS 更远
        val lon = 115.4068
        val lat = 28.2329
        val (gLon, gLat) = NavShare.Coord.wgs84ToGcj02(lon, lat)
        val (bLon, bLat) = NavShare.Coord.wgs84ToBd09(lon, lat)
        val gcjDist = Math.hypot(gLon - lon, gLat - lat)
        val bdDist = Math.hypot(bLon - lon, bLat - lat)
        assertTrue("BD 偏移应大于 GCJ: $bdDist vs $gcjDist", bdDist > gcjDist)
    }

    @Test
    fun navShare_提供高德百度腾讯谷歌和通用() {
        val ids = NavShare.ALL.map { it.id }
        assertTrue(ids.contains("amap"))
        assertTrue(ids.contains("baidu"))
        assertTrue(ids.contains("tencent"))
        assertTrue(ids.contains("google"))
        assertTrue(ids.contains("geo"))
    }

    // ---------------- 屏幕空间聚类 ----------------

    private fun mkSpot(id: Long, lon: Double, lat: Double, photos: Int = 0): Spot {
        // photoCount 不是构造参数，它由 DAO 从照片表统计后填进去，这里手动赋值
        val s = Spot(id = id, lon = lon, lat = lat, name = "点$id")
        s.photoCount = photos
        return s
    }

    @Test
    fun cluster_空列表返回空() {
        val r = SpotClusterer.cluster(emptyList(), 10.0)
        assertTrue(r.clusters.isEmpty())
        assertTrue(r.singles.isEmpty())
    }

    @Test
    fun cluster_街道级完全不聚合() {
        // z16 已到街道级，每个点都要单独显示，否则用户找不到自己的点
        val pts = (1L..5L).map { mkSpot(it, 115.406 + it * 1e-6, 28.232) }
        val r = SpotClusterer.cluster(pts, 16.0)
        assertTrue("不该有簇", r.clusters.isEmpty())
        assertEquals(5, r.singles.size)
    }

    @Test
    fun cluster_相距很近的点会合并() {
        // 两个点相差约 10 米，在 z11 下屏幕上不到 1 像素，必须合并
        val pts = listOf(
            mkSpot(1, 115.4068, 28.2329, photos = 2),
            mkSpot(2, 115.4069, 28.2330, photos = 3),
        )
        val r = SpotClusterer.cluster(pts, 11.0)
        assertEquals("应合成 1 个簇", 1, r.clusters.size)
        assertEquals("簇里 2 个点", 2, r.clusters[0].count)
        assertEquals("照片数累加", 5, r.clusters[0].photoCount)
        assertTrue("不该有单点", r.singles.isEmpty())
    }

    @Test
    fun cluster_相距很远的点不合并() {
        // 相差约 0.05 度（约 5 公里）。z11 下世界宽 256*2^11≈52 万像素，
        // 0.05 度经度约等于 72 像素，刚好超过 70px 的合并半径
        val pts = listOf(
            mkSpot(1, 115.4068, 28.2329),
            mkSpot(2, 115.4568, 28.2329),
        )
        val r = SpotClusterer.cluster(pts, 11.0)
        assertEquals("不该合并", 0, r.clusters.size)
        assertEquals(2, r.singles.size)
    }

    @Test
    fun cluster_层级越低合并得越多() {
        // 40 个点铺开约 0.1 度（约 10 公里）。
        // z9 时整片只有几十像素宽，会收成 1~2 个簇；
        // z13 时同一个 70px 半径只覆盖几百米，会散成很多簇。
        val pts = (1L..40L).map {
            mkSpot(it, 115.380 + (it % 8) * 0.0125, 28.20 + (it / 8) * 0.0125)
        }
        val low = SpotClusterer.cluster(pts, 9.0)
        val high = SpotClusterer.cluster(pts, 13.0)
        // 判据用"被收进簇的点数"而不是"簇的个数"：
        // z13 时点全散开，簇数可能是 0（全是单点），但"合并程度"确实是 0，
        // 拿 0 和 z9 的 1 比大小会得出z13更"不合并"这个正确但反直觉的结论。
        val lowCovered = low.clusters.sumOf { it.count }
        val highCovered = high.clusters.sumOf { it.count }
        assertTrue(
            "z9 收进簇的点数($lowCovered)应多于 z13($highCovered)",
            lowCovered > highCovered
        )
        assertTrue("z9 应该把大多数点收进簇: $lowCovered/40", lowCovered >= 30)
        assertTrue("z13 应该基本散开: $highCovered/40", highCovered <= 20)
    }

    @Test
    fun cluster_簇坐标落在成员范围内() {
        val pts = listOf(
            mkSpot(1, 115.4068, 28.2329),
            mkSpot(2, 115.4070, 28.2331),
        )
        val r = SpotClusterer.cluster(pts, 11.0)
        assertEquals(1, r.clusters.size)
        val c = r.clusters[0]
        assertTrue("经度应在两点之间: ${c.lon}", c.lon in 115.4068..115.4070)
        assertTrue("纬度应在两点之间: ${c.lat}", c.lat in 28.2329..28.2331)
    }

    @Test
    fun cluster_结果是确定的() {
        // 同样的输入必须给同样的输出，否则每次刷新气泡会跳位置
        val pts = (1L..20L).map { mkSpot(it, 115.40 + it * 0.0005, 28.23) }
        val a = SpotClusterer.cluster(pts, 12.0)
        val b = SpotClusterer.cluster(pts.shuffled(), 12.0)
        assertEquals(a.clusters.size, b.clusters.size)
        assertEquals(
            a.clusters.map { it.count }.sorted(),
            b.clusters.map { it.count }.sorted()
        )
    }
}
