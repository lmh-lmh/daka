package com.daka.app.core.track

import com.daka.app.core.db.TrackDao
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * 把当天的轨迹点转成 GeoJSON LineString，塞给地图画线。
 *
 * 两个必要的处理：
 * 1. **按距离抽稀**——静止不动时 GPS 会来回跳几十个点，全画出来是一团噪点。
 *    这里用"离上一个保留点超过 8 米才留"过滤，走路时几乎不掉细节。
 * 2. 少于 2 个点没法画线，返回空集合（地图上空集合是合法的，不用特判图层）。
 */
object TrackGeo {

    private const val MIN_SPACING_M = 8.0
    private const val MAX_POINTS = 5000

    fun lineOf(points: List<TrackDao.Point>): FeatureCollection {
        if (points.size < 2) return FeatureCollection.fromFeatures(arrayOf())
        val kept = ArrayList<Point>(points.size)
        var lastLon = Double.NaN
        var lastLat = Double.NaN
        for (p in points) {
            if (!lastLon.isNaN()) {
                if (distanceM(lastLon, lastLat, p.lon, p.lat) < MIN_SPACING_M) continue
            }
            kept.add(Point.fromLngLat(p.lon, p.lat))
            lastLon = p.lon
            lastLat = p.lat
            if (kept.size >= MAX_POINTS) break
        }
        // 抽稀后可能只剩一个点，那还是画不成线
        if (kept.size < 2) return FeatureCollection.fromFeatures(arrayOf())
        val line = LineString.fromLngLats(kept)
        val f = Feature.fromGeometry(line)
        f.addNumberProperty("n", kept.size)
        return FeatureCollection.fromFeatures(arrayOf(f))
    }

    fun isEmpty(fc: FeatureCollection?): Boolean {
        val fs = fc?.features() ?: return true
        return fs.isEmpty()
    }

    /** 轨迹总长度（米），抽稀后再算，和一屏上看到的线一致。 */
    fun totalMeters(points: List<TrackDao.Point>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        var lastLon = points[0].lon
        var lastLat = points[0].lat
        for (i in 1 until points.size) {
            val p = points[i]
            val d = distanceM(lastLon, lastLat, p.lon, p.lat)
            if (d >= MIN_SPACING_M) {
                sum += d
                lastLon = p.lon
                lastLat = p.lat
            }
        }
        return sum
    }

    /** Haversine。轨迹点和打卡点都用这个，别用 Location.distanceBetween。 */
    fun distanceM(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** "1.2 公里" / "350 米"，给人看的话。 */
    fun prettyMeters(m: Double): String = when {
        m >= 1000 -> String.format(java.util.Locale.US, "%.1f 公里", m / 1000)
        else -> String.format(java.util.Locale.US, "%.0f 米", m)
    }
}
