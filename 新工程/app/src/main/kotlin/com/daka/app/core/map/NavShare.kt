package com.daka.app.core.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.daka.app.core.db.Spot

/**
 * 把一个打卡点分享到第三方导航/地图 App。
 *
 * ## 坐标系问题（这是最容易错的地方）
 *
 * 我们存的是 **WGS-84**（GPS 原始值）。国内第三方导航的实际坐标基准不同：
 *
 * | App | 坐标基准 | 直接把 WGS 丢进去会怎样 |
 * |---|---|---|
 * | 高德 / 腾讯 | GCJ-02（火星坐标） | 偏 300~700 米，落在隔壁街区 |
 * | 百度 | BD-09（在 GCJ 上再加一层偏移） | 偏 500 米以上 |
 * | Google Maps / 国外 | WGS-84 | 正确 |
 *
 * 所以**必须按目标 App 做坐标转换**，否则"导航到这里"会导到错误位置。
 * 这也是之前评估"高德/腾讯底图不可用"的同一个原因。
 *
 * ## 为什么不直接 `geo:lat,lng`
 *
 * `geo:` 是通用 URI，多数国内 App 都能接，但它们按各自基准解释坐标。
 * 高德的官方 URI 方案明确要求传 GCJ-02，百度的要求传 BD-09。
 * 所以按包名分派不同的 URI 和参数才是对的。
 */
object NavShare {

    /** 一个可用的导航目标 */
    data class Target(
        val id: String,
        val label: String,
        val packageName: String,
    )

    /**
     * 候选导航 App。
     *
     * `packageName` 用来判断是否装了；为空表示"任意能接收 geo: 的 App"。
     */
    val ALL: List<Target> = listOf(
        Target("amap", "高德地图", "com.autonavi.minimap"),
        Target("baidu", "百度地图", "com.baidu.BaiduMap"),
        Target("tencent", "腾讯地图", "com.tencent.map"),
        Target("google", "Google 地图", "com.google.android.apps.maps"),
        Target("geo", "其他地图应用", ""),
    )

    /** 这台设备上能用的目标（装了的 + 通用 geo） */
    fun available(ctx: Context): List<Target> {
        val pm = ctx.packageManager
        return ALL.filter { t ->
            if (t.packageName.isEmpty()) {
                hasGeoHandler(ctx)
            } else {
                try {
                    pm.getPackageInfo(t.packageName, 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun hasGeoHandler(ctx: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0"))
        ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /**
     * 生成跳转 Intent。
     *
     * 返回 null 表示这个目标不可用或坐标转换失败。
     */
    fun intentFor(spot: Spot, target: Target, label: String): Intent? {
        // 导航里显示的名字：带上打卡点名，用户到了能对得上
        val name = Uri.encode(spot.name.ifBlank { label })
        return when (target.id) {
            "amap" -> {
                // 高德 URI API 要求 GCJ-02
                val (glon, glat) = Coord.wgs84ToGcj02(spot.lon, spot.lat)
                uriIntent(
                    "androidamap://viewMap?sourceApplication=daka" +
                        "&poiname=$name&lat=$glat&lon=$glon&dev=0"
                )
            }
            "baidu" -> {
                // 百度要求 BD-09
                val (blon, blat) = Coord.wgs84ToBd09(spot.lon, spot.lat)
                uriIntent(
                    "baidumap://map/marker?location=$blat,$blon" +
                        "&title=$name&coord_type=bd09ll&src=android.daka"
                )
            }
            "tencent" -> {
                // 腾讯要求 GCJ-02
                val (glon, glat) = Coord.wgs84ToGcj02(spot.lon, spot.lat)
                uriIntent(
                    "qqmap://map/marker?marker=coord:$glat,$glon;title:$name" +
                        "&referer=daka"
                )
            }
            "google" -> {
                // Google 用 WGS-84，直接给原始值
                uriIntent("geo:${spot.lat},${spot.lon}?q=${spot.lat},${spot.lon}($name)")
            }
            else -> {
                // 通用 geo：给 WGS-84（国际标准）
                uriIntent("geo:${spot.lat},${spot.lon}?q=${spot.lat},${spot.lon}($name)")
            }
        }
    }

    private fun uriIntent(uri: String): Intent? = try {
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 坐标转换。
     *
     * 三个基准的关系：
     * - **WGS-84**：GPS 原始值，国际标准，我们存的就是这个
     * - **GCJ-02**：中国国测局加密后的"火星坐标"，高德/腾讯用
     * - **BD-09**：百度在 GCJ-02 上又加了一层偏移
     *
     * 转换公式是公开的标准算法，误差在 1~2 米内，对"导航到某地"完全够用。
     */
    object Coord {
        private const val A = 6378245.0
        private const val EE = 0.00669342162296594323
        private const val X_PI = Math.PI * 3000.0 / 180.0

        /** 中国大陆范围外不做偏移（公式只在国内有效） */
        private fun outOfChina(lon: Double, lat: Double): Boolean =
            !(73.66 < lon && lon < 135.05 && 3.86 < lat && lat < 53.55)

        private fun tLat(x: Double, y: Double): Double {
            var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
            r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
            r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
            return r
        }

        private fun tLon(x: Double, y: Double): Double {
            var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
            r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
            r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
            return r
        }

        /** WGS-84 → GCJ-02 */
        fun wgs84ToGcj02(lon: Double, lat: Double): Pair<Double, Double> {
            if (outOfChina(lon, lat)) return lon to lat
            var dLat = tLat(lon - 105.0, lat - 35.0)
            var dLon = tLon(lon - 105.0, lat - 35.0)
            val radLat = lat / 180.0 * Math.PI
            var magic = Math.sin(radLat)
            magic = 1 - EE * magic * magic
            val sqrtMagic = Math.sqrt(magic)
            dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
            dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
            return (lon + dLon) to (lat + dLat)
        }

        /** GCJ-02 → WGS-84（迭代反解，精度优于 1 米） */
        fun gcj02ToWgs84(lon: Double, lat: Double): Pair<Double, Double> {
            if (outOfChina(lon, lat)) return lon to lat
            var wLon = lon
            var wLat = lat
            repeat(30) {
                val (gLon, gLat) = wgs84ToGcj02(wLon, wLat)
                val dLon = gLon - lon
                val dLat = gLat - lat
                wLon -= dLon
                wLat -= dLat
                if (Math.abs(dLon) < 1e-9 && Math.abs(dLat) < 1e-9) return wLon to wLat
            }
            return wLon to wLat
        }

        /** GCJ-02 → BD-09 */
        fun gcj02ToBd09(lon: Double, lat: Double): Pair<Double, Double> {
            val z = Math.sqrt(lon * lon + lat * lat) + 0.00002 * Math.sin(lat * X_PI)
            val theta = Math.atan2(lat, lon) + 0.000003 * Math.cos(lon * X_PI)
            val bdLon = z * Math.cos(theta) + 0.0065
            val bdLat = z * Math.sin(theta) + 0.006
            return bdLon to bdLat
        }

        /** WGS-84 → BD-09（先转 GCJ 再转 BD） */
        fun wgs84ToBd09(lon: Double, lat: Double): Pair<Double, Double> {
            val (gLon, gLat) = wgs84ToGcj02(lon, lat)
            return gcj02ToBd09(gLon, gLat)
        }
    }
}
