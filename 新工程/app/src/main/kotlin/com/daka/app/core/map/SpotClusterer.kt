package com.daka.app.core.map

import com.daka.app.core.db.Spot

/**
 * 打卡点的屏幕空间聚类（自己做，不用 MapLibre 内置的 cluster）。
 *
 * ## 为什么不用 MapLibre 的 `GeoJsonOptions.withCluster(true)`
 *
 * 实测过，在这个工程里它不工作：推 1002 个点给 source 后
 * - `querySourceFeatures` 只返回 5 条、`point_count` 一个都没有
 * - 聚类图层渲染出的 feature 数是 **0**
 * - 结果就是低层级"一个标记都看不见"，加上数字气泡更是完全没有
 *
 * 也试过用 symbol 的碰撞检测（`iconAllowOverlap(false)`）做视觉避让，
 * 但 1000 个点挤在 10 公里内时碰撞检测判定"一个都放不下"，直接把标记全隐藏了，
 * 比叠在一起更糟。
 *
 * ## 这里的做法
 *
 * 纯 Kotlin 计算：把经纬度按墨卡托投影换算成"当前缩放级别下的世界像素坐标"，
 * 再按屏幕像素距离做贪心聚类。**不调用 MapLibre 的 projection**——上一次就是被
 * `toScreenLocation` 和 `cameraPosition.zoom` 的坐标系差异（本机 density=2.25 时差 2.36 级）
 * 坑了很久，纯数学换算没有这个问题。
 *
 * 输出两部分：
 * - `clusters`：2 个点以上的簇，画成带数字的气泡
 * - `singles`：独立的点，画成正常标记
 *
 * 效果就是用户要的"县一级比例尺中相近的标识合并"：缩得越远，同样的像素半径
 * 覆盖的实际范围越大，合并得越多；放大后自然散开。
 */
object SpotClusterer {

    /** 合并半径（屏幕像素）。70px 约等于两个标记不重叠的距离 */
    private const val RADIUS_PX = 70.0

    /** 超过这个缩放级别就完全散开，不再聚类（街道级要能看到每一个点） */
    private const val NO_CLUSTER_ZOOM = 15.0

    /** 一个簇 */
    data class Cluster(
        val lon: Double,
        val lat: Double,
        val count: Int,
        val photoCount: Int,
        /** 簇里最新的一个点，用来做"点气泡展开后优先看哪个" */
        val newestSpotId: Long,
    )

    data class Result(
        val clusters: List<Cluster>,
        val singles: List<Spot>,
    )

    /**
     * 执行聚类。
     *
     * @param zoom 当前地图缩放级别
     * @param radiusPx 合并半径（像素）
     */
    fun cluster(
        spots: List<Spot>,
        zoom: Double,
        radiusPx: Double = RADIUS_PX,
    ): Result {
        if (spots.isEmpty()) return Result(emptyList(), emptyList())
        // 放到街道级就不聚了，每个点各自显示
        if (zoom >= NO_CLUSTER_ZOOM) return Result(emptyList(), spots)

        // 世界像素宽度：z0 是 256px，每级翻倍
        val worldPx = 256.0 * Math.pow(2.0, zoom)
        val r2 = radiusPx * radiusPx

        // 先按 id 排序，保证同样的输入永远得到同样的聚类结果
        // （不然每次刷新气泡会跳来跳去）
        val sorted = spots.sortedBy { it.id }

        // 贪心聚类：每个点找最近的已有簇，在半径内就并进去，否则自己开一个
        class Acc(val x: Double, val y: Double) {
            var sumX = x
            var sumY = y
            var count = 1
            var photos = 0
            var newestId = 0L
            var newestAt = Long.MIN_VALUE
            val members = ArrayList<Spot>(1)
        }

        val accs = ArrayList<Acc>()
        for (s in sorted) {
            val (x, y) = mercatorPx(s.lon, s.lat, worldPx)
            var best: Acc? = null
            var bestD = Double.MAX_VALUE
            for (a in accs) {
                val dx = a.sumX / a.count - x
                val dy = a.sumY / a.count - y
                val d = dx * dx + dy * dy
                if (d <= r2 && d < bestD) {
                    bestD = d
                    best = a
                }
            }
            if (best != null) {
                best.sumX += x
                best.sumY += y
                best.count++
                best.photos += s.photoCount
                if (s.updatedAt > best.newestAt) {
                    best.newestAt = s.updatedAt
                    best.newestId = s.id
                }
                best.members.add(s)
            } else {
                accs.add(
                    Acc(x, y).also {
                        it.photos = s.photoCount
                        it.newestAt = s.updatedAt
                        it.newestId = s.id
                        it.members.add(s)
                    }
                )
            }
        }

        val clusters = ArrayList<Cluster>()
        val singles = ArrayList<Spot>()
        for (a in accs) {
            if (a.count == 1) {
                singles.add(a.members[0])
            } else {
                // 簇的坐标用成员的平均值，反算回经纬度
                val cx = a.sumX / a.count
                val cy = a.sumY / a.count
                val (lon, lat) = mercatorPxToLatLon(cx, cy, worldPx)
                clusters.add(
                    Cluster(
                        lon = lon,
                        lat = lat,
                        count = a.count,
                        photoCount = a.photos,
                        newestSpotId = a.newestId,
                    )
                )
            }
        }
        return Result(clusters, singles)
    }

    /** 经纬度 → 世界像素坐标 */
    private fun mercatorPx(lon: Double, lat: Double, worldPx: Double): Pair<Double, Double> {
        val x = (lon + 180.0) / 360.0 * worldPx
        val clamped = lat.coerceIn(-85.05, 85.05)
        val s = Math.sin(Math.toRadians(clamped))
        val y = (0.5 - Math.log((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI)) * worldPx
        return x to y
    }

    /** 世界像素坐标 → 经纬度 */
    private fun mercatorPxToLatLon(x: Double, y: Double, worldPx: Double): Pair<Double, Double> {
        val lon = x / worldPx * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * y / worldPx
        val lat = Math.toDegrees(Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))))
        return lon to lat
    }
}
