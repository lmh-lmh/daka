package com.daka.app.core.map

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.daka.app.core.db.Spot
import com.daka.app.core.util.IconStore
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.style.sources.GeoJsonOptions

/**
 * 标记图层：把打卡点变成 MapLibre 的 GeoJSON source + symbol layer。
 *
 * 为什么不用 `AnnotationManager`（原工程那种 map.addMarker 的写法）：
 * MapLibre 官方明确说超过约 50 个标注就该换 style layer + GeoJSON source，
 * 原话 "Past ~50 items, a style layer over a GeoJSON source is dramatically cheaper,
 * and clustering cheaper still."。style layer 能扛 100,000+ 个点。
 *
 * 关键约束（都是官方文档/issue 里明确写的）：
 * - 每个 feature 必须有**顶层 `id`**，否则 feature state（选中变色）用不了，
 *   因为 `promoteId` 只在 web 端有效
 * - 单点变色用 feature state，不要重新 setGeoJson（官方原话
 *   "re-feeding the source with setGeoJsonSource, is expensive"）
 * - setGeoJson 本身是异步的，不要开 setOverrideSynchronousUpdate(true)，
 *   那个会卡主线程（maplibre-native PR #4092 / issue #4309 "ANR - nativeSetFeatureSync"）
 * - source/layer 必须在 onStyleLoaded 之后才能加
 */
class MarkerLayer(private val ctx: Context) {

    companion object {
        const val SOURCE_ID = "spots"
        const val LAYER_ID = "spots-layer"
        /** 自己算的数字聚合气泡（和上面那个 MapLibre 内置聚类层无关） */
        const val BUBBLE_SOURCE_ID = "spot-bubbles"
        const val BUBBLE_LAYER_ID = "spot-bubbles-icon"
        /** 聚类气泡（圆） */
        const val CLUSTER_LAYER_ID = "spots-cluster"
        /** 聚类气泡里的数字 */
        const val CLUSTER_COUNT_LAYER_ID = "spots-cluster-count"

        /**
         * 聚类参数。
         *
         * `clusterRadius` 的单位**不是像素**，是 1/512 个瓦片（Style Spec 原话
         * "512 represents the width of a tile"），50 大概是屏幕半径 50px 的量级，
         * 跟直觉接近，但别当成精确像素去算。
         * `clusterMaxZoom` 必须小于 source 的 maxzoom，否则高缩放时永远不展开。
         */
        /**
         * 聚类参数。
         *
         * 调参依据（用户要求"县一级比例尺中相近的标识合并，上一级也一样"）：
         * - radius 60：60px 内的点合并。地图缩得越远，同样的 60px 覆盖的实际面积越大，
         *   天然形成"全国→省→市→县"逐级细化，不用手写层级逻辑
         * - clusterMaxZoom 15：到 z16（街道级）才完全展开成一个一个点。
         *   原来设 14，结果 z12~14 这段（正好是市到县之间）点已经开始散开，看着很乱
         * - minPoints 2：两个点靠太近就合并，避免重叠成一坨看不出有几个
         */
        fun clusterOptions(): GeoJsonOptions = GeoJsonOptions()
            .withCluster(false)
            .withClusterRadius(60)
            .withClusterMaxZoom(13)
            .withClusterMinPoints(2)
    }

    /** 图标位图缓存。key = 样式指纹，避免同类标记重复绘制 */
    /**
     * 标记图标的基础尺寸（dp）。
     *
     * 为什么从 96 降到 40：96dp 在 360dpi 平板上接近屏幕宽度的四分之一，
     * 几十个点就把地图糊满了，这是"大小不合适"的直接原因。
     * 40dp 与主流地图 App 的标记尺寸相当。
     */
    private val BASE_DP = 40f

    /**
     * 标记随地图缩放自动变小。
     *
     * 用 MapLibre 的 interpolate 表达式在渲染层做，**不重建位图**：
     * 位图按最大尺寸生成一次，缩放交给 GPU。这样拖动缩放时没有 GC 抖动。
     *
     * 取值依据：低层级（看全国/全省）时标记必须小，否则几十个点互相压住；
     * 放大到街道级（z15+）才恢复原尺寸，才看得清形状和 emoji。
     */
    fun iconSizeExpression(): org.maplibre.android.style.expressions.Expression =
        org.maplibre.android.style.expressions.Expression.interpolate(
            org.maplibre.android.style.expressions.Expression.linear(),
            org.maplibre.android.style.expressions.Expression.zoom(),
            // 缩到全国/全省时标记只占 1/3 大小，密集区也不至于完全重叠成一片
            org.maplibre.android.style.expressions.Expression.stop(3, org.maplibre.android.style.expressions.Expression.literal(0.35f)),
            org.maplibre.android.style.expressions.Expression.stop(7, org.maplibre.android.style.expressions.Expression.literal(0.45f)),
            org.maplibre.android.style.expressions.Expression.stop(10, org.maplibre.android.style.expressions.Expression.literal(0.60f)),
            org.maplibre.android.style.expressions.Expression.stop(13, org.maplibre.android.style.expressions.Expression.literal(0.80f)),
            org.maplibre.android.style.expressions.Expression.stop(16, org.maplibre.android.style.expressions.Expression.literal(1.00f)),
        )

    private val iconCache = LruCache<String, Bitmap>(96)

    /** 当前已注册到 style 的图标 id，避免重复 addImage */
    private val registered = HashSet<String>()

    /**
     * 给单个打卡点生成图标 id（同时把位图注册到 style 里）。
     *
     * 自定义图片图标（iconFile 非空）直接用文件内容做一次解码，失败就退回形状绘制。
     */
    fun iconIdFor(spot: Spot, density: Float, selected: Boolean = false): String {
        val px = (BASE_DP * spot.size * density).toInt().coerceIn(16, 256)
        // 颜色用无符号十六进制，不直接 toString()：
        // Int 的 toString() 对 0xFF2E7D32 这类值会给出负数（-13730510），
        // 图片 id 里带负号是没必要的风险。
        val cr = Integer.toHexString(spot.color)
        val base = when {
            spot.iconFile.isNotEmpty() -> "ic_${spot.iconFile}_$px"
            spot.text.isNotEmpty() -> "tx_${spot.text}_${cr}_$px"
            else -> "sh_${spot.shape}_${cr}_$px"
        }
        // MapLibre 11.13.5 的 Android SDK 没有 feature state API（javap 里搜不到
        // 任何 FeatureState 类），所以"选中态"只能靠换一张带白圈的图标来表达。
        return if (selected) "${base}_sel" else base
    }

    /** 生成（或取缓存）图标位图 */
    fun iconBitmapFor(spot: Spot, density: Float, selected: Boolean = false): Bitmap {
        val px = (BASE_DP * spot.size * density).toInt().coerceIn(16, 256)
        val key = iconIdFor(spot, density, selected)
        iconCache.get(key)?.let { return it }

        val raw: Bitmap = if (spot.iconFile.isNotEmpty()) {
            IconStore.loadIcon(ctx, spot.iconFile, px)
                ?: MarkerShape.render(spot.shape, spot.color, px, badge = badgeOf(spot))
        } else {
            MarkerShape.render(
                shape = spot.shape,
                color = spot.color,
                px = px,
                text = spot.text,
                badge = badgeOf(spot),
            )
        }
        val bmp = if (selected) withSelectionRing(raw, px) else raw
        iconCache.put(key, bmp)
        return bmp
    }

    /** 选中态：给原图标套一圈白边，视觉上"点亮" */
    private fun withSelectionRing(src: Bitmap, px: Int): Bitmap {
        val pad = (px * 0.14f).toInt().coerceAtLeast(6)
        val out = Bitmap.createBitmap(
            src.width + pad * 2, src.height + pad * 2, Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = pad.toFloat()
            color = android.graphics.Color.WHITE
        }
        val cx = out.width / 2f
        val cy = out.height / 2f
        val r = (minOf(src.width, src.height) / 2f) + pad / 2f
        c.drawCircle(cx, cy, r, paint)
        c.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    private fun badgeOf(spot: Spot): String = when {
        spot.photoCount <= 0 -> ""
        spot.photoCount > 99 -> "99+"
        else -> spot.photoCount.toString()
    }

    /**
     * 把一批点转成 GeoJSON FeatureCollection。
     *
     * 为什么每次全量重建字符串而不是增量：MapLibre Android 没有
     * setGeoJsonSourceData（只有 setGeoJson(String/FeatureCollection/Feature/Geometry)），
     * 没有官方增量 API。几千个点的字符串拼接在 IO 线程做，实测可接受；
     * 阶段 2 加视口裁剪后每次只拼视口内的点，量更小。
     */
    fun buildGeoJson(
        spots: List<Spot>,
        density: Float,
        selectedId: Long = -1L,
    ): Pair<String, Map<String, Bitmap>> {
        val features = JSONArray()
        val images = LinkedHashMap<String, Bitmap>()

        for (s in spots) {
            val selected = s.id == selectedId
            val iconId = iconIdFor(s, density, selected)
            if (iconId !in images) {
                images[iconId] = iconBitmapFor(s, density, selected)
            }
            val props = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("icon", iconId)
                put("selected", selected)
                put("photoCount", s.photoCount)
                put("category", s.category)
            }
            val geom = JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(s.lon).put(s.lat))
            }
            features.put(
                JSONObject().apply {
                    put("type", "Feature")
                    put("id", s.id)  // 顶层 id，feature state 必需
                    put("properties", props)
                    put("geometry", geom)
                }
            )
        }

        val fc = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", features)
        }
        return fc.toString() to images
    }

    /**
     * 画一个数字聚合气泡。
     *
     * 视觉：圆形底 + 白色数字 + 粗白描边。
     * 颜色按数量分档（少=橙、中=红、多=紫），用户扫一眼就知道哪片密集。
     *
     * 为什么自己做而不是用 CircleLayer + textField 表达式：
     * 圆和文字是两个图层，缩放时容易错位；而且文字要描边才读得清，
     * 用 Bitmap 一次画好最稳，也省掉一层图层。
     */
    fun bubbleBitmapFor(count: Int, density: Float): Bitmap {
        val key = "bubble_${count}_${density.toInt()}"
        iconCache.get(key)?.let { return it }

        // 数量越大圆越大，但收敛在合理范围，避免一个气泡占满屏幕
        val baseDp = when {
            count >= 200 -> 42f
            count >= 50 -> 36f
            count >= 10 -> 31f
            else -> 27f
        }
        val px = (baseDp * density).toInt().coerceIn(40, 260)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val r = px / 2f

        val fill = when {
            count >= 200 -> 0xFF8E24AA.toInt()   // 紫：极密
            count >= 50 -> 0xFFD81B60.toInt()    // 洋红
            count >= 10 -> 0xFFF4511E.toInt()    // 橙红
            else -> 0xFFFF9800.toInt()           // 橙：少量
        }
        // 外圈白描边：在深色卫星图上把气泡和底图分开
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }
        c.drawCircle(r, r, r, ring)

        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = fill
        }
        c.drawCircle(r, r, r * 0.86f, body)

        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
        }
        val txt = if (count > 999) "999+" else count.toString()
        // 数字长了自动缩字号，否则 3 位数会撑出圆外
        tp.textSize = when {
            txt.length >= 4 -> px * 0.30f
            txt.length == 3 -> px * 0.36f
            txt.length == 2 -> px * 0.42f
            else -> px * 0.46f
        }
        val fm = tp.fontMetrics
        c.drawText(txt, r, r - (fm.ascent + fm.descent) / 2f, tp)

        iconCache.put(key, bmp)
        return bmp
    }

    /**
     * 把聚类结果转成两份 GeoJSON：气泡一份、单点一份。
     *
     * 单点仍然复用原来的 [buildGeoJson]，所以标记样式、选中环、徽标全都不变。
     */
    fun buildBubbleGeoJson(
        clusters: List<SpotClusterer.Cluster>,
        density: Float,
    ): Pair<String, Map<String, Bitmap>> {
        val features = JSONArray()
        val images = LinkedHashMap<String, Bitmap>()
        for (cl in clusters) {
            val iconId = "bubble_${cl.count}_${density.toInt()}"
            if (iconId !in images) images[iconId] = bubbleBitmapFor(cl.count, density)
            val props = JSONObject().apply {
                put("count", cl.count)
                put("photoCount", cl.photoCount)
                put("spotId", cl.newestSpotId)
                put("icon", iconId)
                // 气泡按屏幕像素固定大小，不随缩放变 —— 它是"信息"，不是"地标"
                put("lon", cl.lon)
                put("lat", cl.lat)
            }
            val geom = JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(cl.lon).put(cl.lat))
            }
            features.put(
                JSONObject().apply {
                    put("type", "Feature")
                    put("properties", props)
                    put("geometry", geom)
                }
            )
        }
        val fc = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", features)
        }
        return fc.toString() to images
    }

    fun clearRegistered() {
        registered.clear()
    }

    fun wasRegistered(id: String): Boolean = id in registered

    fun markRegistered(id: String) {
        registered.add(id)
    }
}
