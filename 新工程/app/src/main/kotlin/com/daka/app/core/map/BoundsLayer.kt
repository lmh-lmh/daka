package com.daka.app.core.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 行政区边界图层。
 *
 * 显示规则跟分层视图走：
 *   L0 全国 → 画省界
 *   L1 省内 → 画市界
 *   L2 市内 → 画区县界
 *   L3 打卡点 → 不画边界（要让位给打卡点图标和照片）
 *
 * 为什么用 GeoJSON 的 Polygon 而不是 LineString：MapLibre 的 fill 图层能同时画
 * 半透明填色和描边，一条 Polygon + fill-outline-color 就够了；用 LineString 得把
 * 每个环的闭合点手动处理，且相邻区域共享边界会画两遍（线宽翻倍、颜色变深）。
 */
object BoundsLayer {

    const val SOURCE_ID = "bounds"
    /** 填色层（很淡，只用来区分区块） */
    const val FILL_LAYER_ID = "bounds-fill"
    /** 描边层 */
    const val LINE_LAYER_ID = "bounds-line"

    /**
     * 生成某一级边界的 GeoJSON。
     *
     * @param codes 只画这些单元的边界；为空表示画全部（L0 全国用）
     * @param level 0=省 1=市 2=区县
     */
    fun geoJson(level: Int, codes: Collection<String>?): String {
        val units = when (level) {
            0 -> AdminTree.boundsOfProvinces()
            1 -> AdminTree.boundsOfCities()
            else -> AdminTree.boundsOfDistricts()
        }
        val features = JSONArray()
        for (u in units) {
            if (codes != null && codes.isNotEmpty() && u.code !in codes) continue
            val rings = JSONArray()
            for (ring in u.rings) {
                val arr = JSONArray()
                var i = 0
                while (i + 1 < ring.size) {
                    arr.put(JSONArray().put(ring[i].toDouble().let { round5(it) })
                        .put(ring[i + 1].toDouble().let { round5(it) }))
                    i += 2
                }
                // GeoJSON Polygon 的环必须首尾闭合，且闭合后至少 4 个点
                // （三角形 3 点 + 闭合点 1 = 4）。之前把"闭合"写在长度判断之后，
                // 导致只有 3 个点的环没闭合就塞进去，MapLibre 直接报
                // "Polygon must be described by 4 or more coordinate points" 并整批丢弃。
                if (arr.length() >= 3) {
                    val first = arr.getJSONArray(0)
                    val last = arr.getJSONArray(arr.length() - 1)
                    if (first.getDouble(0) != last.getDouble(0) ||
                        first.getDouble(1) != last.getDouble(1)
                    ) {
                        arr.put(JSONArray().put(first.getDouble(0)).put(first.getDouble(1)))
                    }
                }
                // 闭合之后必须 >= 4 个点，否则丢这块（不丢会让整批 GeoJSON 解析失败）
                if (arr.length() >= 4) {
                    rings.put(arr)
                }
            }
            if (rings.length() == 0) continue
            val props = JSONObject().apply {
                put("code", u.code)
                put("name", u.name)
            }
            // 一个单元可能有多块（海南、舟山群岛），用 MultiPolygon 统一表达
            val geom = JSONObject().apply {
                put("type", "Polygon")
                put("coordinates", JSONArray().put(rings.getJSONArray(0)))
            }
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", props)
                put("geometry", geom)
            })
            // 剩余块单独作为 feature（省得拼 MultiPolygon 的嵌套数组）
            for (r in 1 until rings.length()) {
                features.put(JSONObject().apply {
                    put("type", "Feature")
                    put("properties", props)
                    put("geometry", JSONObject().apply {
                        put("type", "Polygon")
                        put("coordinates", JSONArray().put(rings.getJSONArray(r)))
                    })
                })
            }
        }
        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", features)
        }.toString()
    }

    /** 坐标保留 5 位小数（约 1 米），够用且能显著压小 JSON */
    private fun round5(v: Double): Double = Math.round(v * 100000.0) / 100000.0

    fun emptyCollection(): String = """{"type":"FeatureCollection","features":[]}"""
}
