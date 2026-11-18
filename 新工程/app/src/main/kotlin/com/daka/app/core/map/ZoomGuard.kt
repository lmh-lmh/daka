package com.daka.app.core.map

/**
 * 影像精度守卫。
 *
 * 为什么需要它：Esri 的世界影像在不同地区能放到的最大层级差很多，实测（28 个采样点）：
 * - z20：纽约、洛杉矶、悉尼、奥克兰、开普敦
 * - z19：北京、上海、南昌、东京、伦敦、巴黎、开罗…
 * - **z18：宜春、赣州、九江、上饶、吉安、抚州**
 *
 * 超过这个层级，Esri 返回的是 2521 字节的灰底图（图上写着 "Map data not yet available"）。
 * 如果不干预，用户放大就会看到一片灰白，以为是 App 卡了。
 *
 * 处理办法：
 * 1. source 的 maxzoom 设 20，让 MapLibre 对超出部分做 overzoom（模糊放大），不出灰块
 * 2. 进到已知精度低的区域，用 setMaxZoomPreference 把可放大的上限压下来
 * 3. 顶上一行提示，说清"这里只能到这一级"
 */
object ZoomGuard {

    /** 单个区域：中心点 + 半径（公里）+ 该区域能放到的最大层级 */
    data class Region(val name: String, val lon: Double, val lat: Double, val radiusKm: Double, val maxZoom: Int)

    /**
     * 已知只到 z18 的区域。目前只写我实测过的江西六个地市——
     * 我没有全国数据，所以不做全国性的结论，未列出的地方按全球保底 z18 处理，
     * 但会通过占位图特征做兜底判断（见 looksLikePlaceholder）。
     */
    private val KNOWN_LOW = listOf(
        Region("宜春", 114.4167, 27.8000, 60.0, 18),
        Region("赣州", 114.9333, 25.8333, 60.0, 18),
        Region("九江", 115.9833, 29.7333, 60.0, 18),
        Region("上饶", 117.9667, 28.4500, 60.0, 18),
        Region("吉安", 114.9833, 27.1167, 60.0, 18),
        Region("抚州", 116.3500, 27.9500, 60.0, 18),
    )

    /**
     * 已知比全球保底更清晰的大城市。同样是实测得出的，只收录测过的点，
     * 不测的地方不瞎猜——宁可保守给 18，也不要让用户放大后看到灰块。
     *
     * 来源：28 点「最高可用层级」实测（research/probe_maxzoom.py），2026-11-12。
     */
    private val KNOWN_HIGH = listOf(
        // z20：实测这几个城市能放到 20
        Region("纽约", -74.0060, 40.7128, 40.0, 20),
        Region("洛杉矶", -118.2437, 34.0522, 40.0, 20),
        Region("悉尼", 151.2093, -33.8688, 40.0, 20),
        Region("奥克兰", 174.7633, -36.8485, 30.0, 20),
        Region("开普敦", 18.4241, -33.9249, 30.0, 20),
        // z19：实测能放到 19
        Region("北京", 116.4074, 39.9042, 50.0, 19),
        Region("上海", 121.4737, 31.2304, 50.0, 19),
        Region("南昌", 115.8582, 28.6829, 50.0, 19),
        Region("东京", 139.6917, 35.6895, 50.0, 19),
        Region("新加坡", 103.8198, 1.3521, 30.0, 19),
        Region("孟买", 72.8777, 19.0760, 30.0, 19),
        Region("伦敦", -0.1276, 51.5072, 40.0, 19),
        Region("巴黎", 2.3522, 48.8566, 40.0, 19),
        Region("莫斯科", 37.6173, 55.7558, 40.0, 19),
        Region("开罗", 31.2357, 30.0444, 40.0, 19),
        Region("内罗毕", 36.8219, -1.2921, 30.0, 19),
        Region("墨西哥城", -99.1332, 19.4326, 40.0, 19),
        Region("里约热内卢", -43.1729, -22.9068, 40.0, 19),
        Region("布宜诺斯艾利斯", -58.3816, -34.6037, 40.0, 19),
        Region("利马", -77.0428, -12.0464, 30.0, 19),
        Region("雷克雅未克", -21.9426, 64.1466, 30.0, 19),
        Region("迪拜", 55.2708, 25.2048, 40.0, 19),
    )

    /** 全球保底：实测 28 个采样点里 z18 全部有影像 */
    const val GLOBAL_SAFE = 18

    /** source 声明上限，超过就靠 overzoom */
    const val SOURCE_MAX = 20

    /**
     * 地图 zoom 与「实际请求的瓦片层级」之间的差，实测是 1。
     *
     * 真机日志（2026-11-13，小米平板 5）：
     *   状态栏 z10.0 -> 请求 tile/11/    状态栏 z18.0 -> 请求 tile/19/
     * 原因：Esri 的瓦片是 256 像素，而 MapLibre 里 zoom 的语义是按 512 像素瓦片定义的，
     * 所以 tileSize=256 的 raster source 会恒定请求 zoom+1 级，这是规范行为不是 bug。
     *
     * 这条差值的后果很实在：宜春的瓦片只到 z18，地图放到 z18 时去取 z19，
     * 拿回来的是灰底 "Map data not yet available"。所以给用户的可缩放上限
     * 必须比瓦片上限低一级，否则一定撞上占位图。
     */
    const val TILE_ZOOM_OFFSET = 1

    /**
     * 给一个位置算出它最高能放到第几级。
     *
     * 顺序：先看命中哪个已知区域，命中多个取最清晰的那个；都没命中就给全球保底 18。
     * 保底取 18 而不是 19，是因为实测 z18 在 28 个采样点里全部有影像，
     * 而 z19 只有 22/28——宁可让用户多看到一点模糊，也不要让他看到 "Map data not yet available"。
     */
    fun maxZoomFor(lon: Double, lat: Double): Int {
        var best = GLOBAL_SAFE
        for (r in KNOWN_HIGH) {
            if (distanceKm(lon, lat, r.lon, r.lat) <= r.radiusKm && r.maxZoom > best) best = r.maxZoom
        }
        for (r in KNOWN_LOW) {
            if (distanceKm(lon, lat, r.lon, r.lat) <= r.radiusKm) {
                // 命中明确"只到 18"的区域，以它为准，不能被别的高精度条目盖过去
                return r.maxZoom
            }
        }
        return best
    }

    /** 命中的区域名，用来说人话提示；没命中返回 null */
    fun regionNameFor(lon: Double, lat: Double): String? {
        for (r in KNOWN_LOW) {
            if (distanceKm(lon, lat, r.lon, r.lat) <= r.radiusKm) return r.name
        }
        for (r in KNOWN_HIGH) {
            if (distanceKm(lon, lat, r.lon, r.lat) <= r.radiusKm) return r.name
        }
        return null
    }

    /**
     * 平移到新位置后该给 MapLibre 的 maxZoomPreference。
     *
     * 注意这里要减去 [TILE_ZOOM_OFFSET]：maxZoomFor 给的是「这块地能拿到的最深瓦片层级」，
     * 而 maxZoomPreference 限制的是「地图 zoom」。两者差 1，不减就会在地图放到瓦片上限那一级时
     * 去请求下一级瓦片，直接撞上 Esri 的灰底占位图（宜春就是这样白屏的）。
     */
    fun preferenceFor(lon: Double, lat: Double): Double {
        val tileMax = minOf(maxZoomFor(lon, lat), SOURCE_MAX)
        return (tileMax - TILE_ZOOM_OFFSET).toDouble()
    }

    /**
     * 瓦片字节流看起来是不是 Esri 的占位图。
     *
     * 实测特征：2521 字节、灰度单一色占比约 94%、图上印着 "Map data not yet available"。
     * 真实影像的众数占比通常 <20%。这里用字节数加粗判，真正的判色需要解码，
     * 放在 TileProbe 里按需做（避免每次拉瓦片都解码损耗性能）。
     */
    fun looksLikePlaceholder(bytes: ByteArray): Boolean = looksLikePlaceholder(bytes.size)

    /**
     * 只按字节数判。有的调用方拿到的是 Content-Length 或已落盘文件长度，
     * 不必把整个瓦片读进内存——占位图固定 2521 字节，给一点余量防服务端微调。
     */
    fun looksLikePlaceholder(size: Int): Boolean = size in 2400..2700

    /** 两点的球面距离（公里），Haversine */
    fun distanceKm(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
