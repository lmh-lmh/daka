package com.daka.app.core.map

import org.json.JSONObject

/**
 * 在线卫星底图配置。
 *
 * 实测结论（见 00-已核实事实清单.md）：
 * - Esri World_Imagery 是全球唯一「免 Key + 全覆盖 + WGS84」可用的源
 * - 但不同地区能放的**最大层级差很多**：纽约/洛杉矶/悉尼能到 z20，北京/上海/南昌到 z19，
 *   宜春/赣州/九江/上饶/吉安/抚州只到 z18（z19 是 2521 字节的灰底占位图）
 * - 所以 source 的 maxzoom 设 20（让 MapLibre 自己 overzoom 模糊放大），
 *   再由 ZoomGuard 按区域动态下调 maxZoomPreference
 */
object BaseMap {

    /** Esri World Imagery，注意 URL 里是 {z}/{y}/{x}，y 在 x 前 */
    const val ESRI_TILES =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** Esri 官方要求在图上显示这个来源声明（从服务元数据 copyrightText 读来的） */
    const val ESRI_ATTRIBUTION =
        "Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community"

    /** EOX Sentinel-2 云免费全球底图，只到 z17，作为连不上 Esri 时的兜底 */
    const val EOX_TILES =
        "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg"

    const val EOX_ATTRIBUTION =
        "EOxCloudless by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024), CC BY-NC-SA 4.0"

    /** 全球保底层级：实测 28 个采样点 z18 全都有影像 */
    const val GLOBAL_SAFE_MAX_ZOOM = 18

    /** source 声明上限。设 20 让缺图区域靠 overzoom 模糊放大，而不是留灰块 */
    const val SOURCE_MAX_ZOOM = 20

    /** 底图种类 */
    enum class Kind(val label: String) {
        ESRI("卫星影像"),
        EOX("全球概览"),
    }

    /**
     * 生成 MapLibre style JSON。
     *
     * 两个必须显式写对的参数：
     * - `tileSize` 必须写 256，MapLibre raster source 默认是 512，
     *   不写会按 z-1 请求再放大一倍，画质直接掉一半
     * - `maxzoom` 必须按源的真实上限设，超过上限会不停请求占位图，白费流量还难看
     */
    fun styleJson(kind: Kind): String {
        val tiles = when (kind) {
            Kind.ESRI -> ESRI_TILES
            Kind.EOX -> EOX_TILES
        }
        val attribution = when (kind) {
            Kind.ESRI -> ESRI_ATTRIBUTION
            Kind.EOX -> EOX_ATTRIBUTION
        }
        val maxzoom = when (kind) {
            Kind.ESRI -> SOURCE_MAX_ZOOM
            Kind.EOX -> 17
        }

        return JSONObject().apply {
            put("version", 8)
            put("name", "daka-${kind.name.lowercase()}")
            put(
                "sources", JSONObject().put(
                    SOURCE_ID, JSONObject().apply {
                        put("type", "raster")
                        put("tiles", org.json.JSONArray().put(tiles))
                        put("tileSize", 256)
                        put("minzoom", 0)
                        put("maxzoom", maxzoom)
                        put("attribution", attribution)
                    }
                )
            )
            // 背景色：瓦片没加载出来时是深色而不是刺眼的白
            put(
                "layers", org.json.JSONArray()
                    .put(
                        JSONObject().apply {
                            put("id", "bg")
                            put("type", "background")
                            put("paint", JSONObject().put("background-color", "#0E1116"))
                        }
                    )
                    .put(
                        JSONObject().apply {
                            put("id", LAYER_ID)
                            put("type", "raster")
                            put("source", SOURCE_ID)
                            put(
                                "paint",
                                JSONObject()
                                    .put("raster-opacity", 1.0)
                                    // 轻微提亮暗部，卫星图在城市里常常偏暗
                                    .put("raster-brightness-min", 0.0)
                                    .put("raster-brightness-max", 1.0)
                            )
                        }
                    )
            )
        }.toString()
    }

    const val SOURCE_ID = "basemap"
    const val LAYER_ID = "basemap-layer"
}
