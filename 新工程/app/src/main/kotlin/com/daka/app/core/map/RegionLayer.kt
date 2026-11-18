package com.daka.app.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import com.daka.app.core.thumb.ThumbLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分层气泡图层。
 *
 * 和打卡点图层（MarkerLayer）完全分开：一个是"代表一整个省/市"的汇总气泡，
 * 一个是"一个具体打卡点"。混在一起会让 filter 和图标注册都变难，
 * 而且两者显隐时机不同（分层气泡在 L0~L2，打卡点在 L3）。
 *
 * 视觉上只做一件事：**一个胶囊里写「地名 · 数量」**。
 * 不做边界线、不做填色、不按分类变色 —— 这是"不要太过繁琐"的落实。
 * 靠数量微调大小和颜色深浅，一眼看出哪些地方去得多。
 */
class RegionLayer(private val ctx: Context) {

    companion object {
        const val SOURCE_ID = "regions"
        const val HALO_LAYER_ID = "regions-halo"
        const val PILL_LAYER_ID = "regions-pill"
        const val COVER_LAYER_ID = "regions-cover"
        const val LABEL_LAYER_ID = "regions-label"
        /** 底圆：不依赖图片资源，用来兜底验证位置与渲染是否正常 */
        const val DOT_LAYER_ID = "regions-dot"
    }

    /** 气泡图标的缓存。key = 名字+数量+样式指纹 */
    private val iconCache = LruCache<String, Bitmap>(48)

    /** 已注册到当前 style 的图标 id */
    private val registered = HashSet<String>()

    fun clearRegistered() = registered.clear()

    fun wasRegistered(id: String): Boolean = id in registered

    fun markRegistered(id: String) = registered.add(id)

    /**
     * 画一个气泡胶囊。
     *
     * 设计取舍：
     * - 数量越大，胶囊越宽越高、底色越深（绿 → 深绿 → 橙），不用数字很难一眼分轻重
     * - 文字用白色加粗，卫星底图上无论深浅都读得清
     * - 高 DPI 屏上按 density 放大，否则会糊
     */
    fun renderBubble(name: String, count: Int, density: Float): Bitmap {
        val d = density.coerceAtLeast(1f)
        val text = if (count > 0) "$name · $count" else name

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * d
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = textPaint.measureText(text)
        val padH = 12f * d
        val padV = 7f * d
        // 数量越多，胶囊越饱满一点（最多 +30%）
        val weight = (1f + (count.coerceIn(0, 100) / 100f) * 0.3f)
        val w = (textW + padH * 2).toInt().coerceAtLeast((36 * d).toInt())
        val h = ((textPaint.textSize + padV * 2) * weight).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 底色按数量分档：少量浅绿、中量绿、多了转暖
        val bg = when {
            count >= 50 -> 0xFFE8590C.toInt()      // 橙：去得多
            count >= 10 -> 0xFF2E7D32.toInt()      // 深绿：常去
            count >= 3 -> 0xFF43A047.toInt()       // 绿
            else -> 0xFF558B2F.toInt()             // 浅绿：只去过一两次
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        val r = h / 2f
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), r, r, body)

        // 一圈半透明白描边，在深色影像上不糊
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            color = 0x99FFFFFF.toInt()
        }
        c.drawRoundRect(RectF(0.8f, 0.8f, w - 0.8f, h - 0.8f), r, r, stroke)

        // 文字垂直居中
        val fm = textPaint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(text, padH, baseline, textPaint)

        return bmp
    }

    /** 图标 id：同一个名字+数量+density 复用一张位图 */
    fun iconIdFor(bubble: RegionAggregator.Bubble, density: Float): String {
        val q = when {
            bubble.spotCount >= 50 -> "a"
            bubble.spotCount >= 10 -> "b"
            bubble.spotCount >= 3 -> "c"
            else -> "d"
        }
        return "rg_${bubble.level}_${q}_${bubble.name.hashCode()}_${bubble.spotCount}_${density.toInt()}"
    }

    fun iconBitmapFor(bubble: RegionAggregator.Bubble, density: Float): Bitmap {
        val key = iconIdFor(bubble, density)
        iconCache.get(key)?.let { return it }
        val bmp = renderBubble(bubble.name, bubble.spotCount, density)
        iconCache.put(key, bmp)
        return bmp
    }

    /**
     * 把气泡列表转成 GeoJSON。
     *
     * 每个 feature 带顶层 id（虽然是分层气泡，不需要 feature state，但保持一致更省心），
     * properties 里放：name / count / icon / level / key。
     */
    fun buildGeoJson(
        bubbles: List<RegionAggregator.Bubble>,
        density: Float,
    ): Pair<String, Map<String, Bitmap>> {
        val features = JSONArray()
        val images = LinkedHashMap<String, Bitmap>()

        for (b in bubbles) {
            val iconId = iconIdFor(b, density)
            if (iconId !in images) images[iconId] = iconBitmapFor(b, density)

            val props = JSONObject().apply {
                put("key", b.key)
                put("name", b.name)
                put("count", b.spotCount)
                put("photoCount", b.photoCount)
                put("level", b.level)
                put("icon", iconId)
            }
            val geom = JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(b.lon).put(b.lat))
            }
            features.put(
                JSONObject().apply {
                    put("type", "Feature")
                    put("id", b.key)
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

    fun emptyCollection(): String = """{"type":"FeatureCollection","features":[]}"""

    /** 释放缓存（离开页面时调） */
    fun clear() {
        iconCache.evictAll()
        registered.clear()
    }
}
