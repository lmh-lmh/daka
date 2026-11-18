package com.daka.app.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 标记图标的形状与绘制。
 *
 * 原工程用的是「样式包」——把颜色和形状捆成 8 个固定组合（red_circle / blue_diamond…），
 * 理由是"避免组合太多更难辨认"。但用户这次明确要「多样化、最好能自定义」，
 * 所以这里推翻原设计，拆成三个独立维度：
 *   形状（10 种）× 任意颜色 × 大小（0.6~2.0）
 * 再叠加两个更强的自定义手段：自定义图片图标、文字/emoji。
 *
 * 优先级：iconFile（自定义图片）> text（emoji）> shape+color
 */
object MarkerShape {

    /** 所有可用形状的名称（落库用这些字符串，不要改，改了要写迁移） */
    val ALL = listOf(
        "pin",      // 水滴定位针（默认）
        "circle",   // 圆
        "square",   // 方
        "triangle", // 三角
        "star",     // 五角星
        "diamond",  // 菱形
        "heart",    // 心
        "flag",     // 小旗
        "hexagon",  // 六边形
        "camera",   // 相机造型
    )

    /** 给人看的名字 */
    fun label(shape: String): String = when (shape) {
        "pin" -> "定位针"
        "circle" -> "圆形"
        "square" -> "方形"
        "triangle" -> "三角"
        "star" -> "星形"
        "diamond" -> "菱形"
        "heart" -> "心形"
        "flag" -> "小旗"
        "hexagon" -> "六边"
        "camera" -> "相机"
        else -> shape
    }

    /**
     * 把标记画成 Bitmap，交给 MapLibre 当图标。
     *
     * 尺寸：基准 96dp，再乘 size 倍数，再乘屏幕密度。为什么不直接用矢量图标：
     * MapLibre 的 symbol layer 要的是位图，且位图能一次性画好复用，避免每帧重画 Path。
     *
     * @param text 非空时画文字/emoji，忽略 shape
     * @param px  期望的像素边长（已含密度与 size）
     */
    fun render(
        shape: String,
        color: Int,
        px: Int,
        text: String = "",
        badge: String = "",
    ): Bitmap {
        val size = px.coerceIn(24, 512)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.045f
        }
        val f = size.toFloat()

        if (text.isNotEmpty()) {
            // 文字/emoji：先画一个圆底衬，再画字
            p.color = color
            c.drawCircle(f / 2, f / 2, f * 0.44f, p)
            c.drawCircle(f / 2, f / 2, f * 0.44f, stroke)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = f * 0.52f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT
            }
            val metrics = tp.fontMetrics
            val baseline = f / 2 - (metrics.ascent + metrics.descent) / 2
            c.drawText(text.take(2), f / 2, baseline, tp)
        } else {
            drawShape(c, p, stroke, shape, f)
        }

        // 右下角小角标：显示照片张数
        if (badge.isNotEmpty()) {
            val r = f * 0.20f
            val cx = f - r - f * 0.04f
            val cy = f - r - f * 0.04f
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFE53935.toInt()
                style = Paint.Style.FILL
            }
            c.drawCircle(cx, cy, r, bp)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = r * 1.15f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val metrics = tp.fontMetrics
            val baseline = cy - (metrics.ascent + metrics.descent) / 2
            c.drawText(badge, cx, baseline, tp)
        }
        return bmp
    }

    private fun drawShape(c: Canvas, p: Paint, stroke: Paint, shape: String, f: Float) {
        val cx = f / 2
        val r = f * 0.40f
        when (shape) {
            "pin" -> {
                // 水滴形：上半圆 + 下尖
                val path = Path().apply {
                    moveTo(cx, f * 0.94f)
                    cubicTo(cx - r * 0.86f, f * 0.60f, cx - r, f * 0.44f, cx - r, f * 0.36f)
                    arcTo(RectF(cx - r, f * 0.10f, cx + r, f * 0.10f + 2 * r), 180f, 180f, false)
                    cubicTo(cx + r, f * 0.44f, cx + r * 0.86f, f * 0.60f, cx, f * 0.94f)
                    close()
                }
                c.drawPath(path, p)
                c.drawPath(path, stroke)
                // 中心白点
                val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                c.drawCircle(cx, f * 0.36f, r * 0.34f, wp)
            }

            "circle" -> {
                c.drawCircle(cx, cx, r, p)
                c.drawCircle(cx, cx, r, stroke)
            }

            "square" -> {
                val rc = RectF(cx - r * 0.88f, cx - r * 0.88f, cx + r * 0.88f, cx + r * 0.88f)
                c.drawRoundRect(rc, r * 0.22f, r * 0.22f, p)
                c.drawRoundRect(rc, r * 0.22f, r * 0.22f, stroke)
            }

            "triangle" -> {
                val path = Path().apply {
                    moveTo(cx, f * 0.10f)
                    lineTo(cx + r * 0.95f, f * 0.86f)
                    lineTo(cx - r * 0.95f, f * 0.86f)
                    close()
                }
                c.drawPath(path, p)
                c.drawPath(path, stroke)
            }

            "star" -> {
                c.drawPath(starPath(cx, cx, r, r * 0.44f, 5), p)
                c.drawPath(starPath(cx, cx, r, r * 0.44f, 5), stroke)
            }

            "diamond" -> {
                val path = Path().apply {
                    moveTo(cx, f * 0.08f)
                    lineTo(cx + r * 0.88f, cx)
                    lineTo(cx, f * 0.92f)
                    lineTo(cx - r * 0.88f, cx)
                    close()
                }
                c.drawPath(path, p)
                c.drawPath(path, stroke)
            }

            "heart" -> {
                val path = Path().apply {
                    moveTo(cx, f * 0.88f)
                    cubicTo(cx - r * 1.5f, f * 0.52f, cx - r * 0.92f, f * 0.12f, cx, f * 0.36f)
                    cubicTo(cx + r * 0.92f, f * 0.12f, cx + r * 1.5f, f * 0.52f, cx, f * 0.88f)
                    close()
                }
                c.drawPath(path, p)
                c.drawPath(path, stroke)
            }

            "flag" -> {
                val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    strokeWidth = f * 0.07f
                }
                c.drawRoundRect(
                    RectF(cx - r * 1.05f, f * 0.10f, cx - r * 0.90f, f * 0.92f),
                    f * 0.03f, f * 0.03f, pole
                )
                val path = Path().apply {
                    moveTo(cx - r * 0.90f, f * 0.14f)
                    lineTo(cx + r * 1.0f, f * 0.30f)
                    lineTo(cx - r * 0.90f, f * 0.50f)
                    close()
                }
                c.drawPath(path, p)
                c.drawPath(path, stroke)
            }

            "hexagon" -> {
                val path = Path()
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i - 90.0)
                    val x = cx + r * Math.cos(a).toFloat()
                    val y = cx + r * Math.sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
                c.drawPath(path, stroke)
            }

            "camera" -> {
                val body = RectF(cx - r, cx - r * 0.62f, cx + r, cx + r * 0.72f)
                c.drawRoundRect(body, r * 0.20f, r * 0.20f, p)
                c.drawRoundRect(body, r * 0.20f, r * 0.20f, stroke)
                // 镜头
                val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                c.drawCircle(cx, cx + r * 0.05f, r * 0.42f, lens)
                val lens2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = color2(color)
                    style = Paint.Style.FILL
                }
                c.drawCircle(cx, cx + r * 0.05f, r * 0.26f, lens2)
                // 取景器凸起
                c.drawRoundRect(
                    RectF(cx - r * 0.30f, cx - r * 0.82f, cx + r * 0.30f, cx - r * 0.58f),
                    r * 0.08f, r * 0.08f, p
                )
            }
        }
    }

    private fun color2(color: Int): Int {
        return Color.argb(255, Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2)
    }

    /** 生成 n 角星路径 */
    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float, points: Int): Path {
        val path = Path()
        for (i in 0 until points * 2) {
            val rr = if (i % 2 == 0) outer else inner
            val a = Math.toRadians(180.0 / points * i - 90.0)
            val x = cx + rr * Math.cos(a).toFloat()
            val y = cy + rr * Math.sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** 预制的一组颜色：用户也可以选任意色，这些只是快捷入口 */
    val PALETTE = listOf(
        0xFF2E7D32.toInt(), // 绿
        0xFFE53935.toInt(), // 红
        0xFF1E88E5.toInt(), // 蓝
        0xFFFB8C00.toInt(), // 橙
        0xFF8E24AA.toInt(), // 紫
        0xFF00897B.toInt(), // 青
        0xFFFDD835.toInt(), // 黄
        0xFF6D4C41.toInt(), // 棕
        0xFFEC407A.toInt(), // 粉
        0xFF546E7A.toInt(), // 蓝灰
        0xFFFFFFFF.toInt(), // 白
        0xFF000000.toInt(), // 黑
    )

    /** 一组常用 emoji，方便快速给点和分类做视觉区分 */
    val EMOJI = listOf(
        "📷", "📍", "⭐", "❤️", "🍜", "☕", "🏞", "🧭", "🏠", "🏢",
        "🎣", "⛰", "🌊", "🌸", "🍺", "🎵", "🚩", "❗", "✅", "🏆",
    )
}
