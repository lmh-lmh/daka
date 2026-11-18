package com.daka.app.core.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.daka.app.core.db.Photo
import com.daka.app.core.db.Spot
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 打卡卡片图：把「一个打卡点 + 它的封面照片」画成一张 1080×1920 的竖图，可以直接分享到微信。
 *
 * 两个关键决定：
 * 1. **默认不显示精确坐标**。分享到社交网络时精确经纬度等于暴露住址，
 *    这里默认只显示城市级的模糊位置，用户手动打开"显示坐标"才写上去。
 * 2. 全部用 Canvas 手画，不引第三方图库。字体用系统默认，不嵌字体文件（省几 MB 包体积）。
 */
object ShareCard {

    private const val W = 1080
    private const val H = 1920
    private const val MARGIN = 72f

    /**
     * 卡片模板。三套的差别不只是配色，版式也不一样：
     * - DARK：深色满版，封面铺满上半部分，信息压在下半部分。适合照片本身很亮的场景。
     * - POLAROID：米白底 + 白框拍立得，照片四边留白，像贴在手账上。适合日常/美食。
     * - MAP：深灰底 + 左侧一条竖向色带（取分类色），封面占右半。适合风景/旅行。
     *
     * 没有做成"任意配色"是因为分享卡片是拼版式的排版，颜色一变字号和留白就要跟着变，
     * 给用户一个调色盘只会做出一堆难看的卡片。给三套调好的，比给一堆旋钮好。
     */
    enum class Template(val label: String, val desc: String) {
        DARK("深色经典", "封面满铺，适合亮照片"),
        POLAROID("拍立得", "米白留白，像贴在手账"),
        MAP("旅行条带", "左侧色带，适合风景"),
    }

    /**
     * 生成卡片。
     *
     * @param showCoord 是否在卡片上印精确坐标，默认 false（脱敏）
     * @param template  版式，默认深色经典
     * @return 保存到相册后的 content uri；失败返回 null
     */
    fun build(
        ctx: Context,
        spot: Spot,
        photos: List<Photo>,
        cover: Bitmap?,
        showCoord: Boolean = false,
        template: Template = Template.DARK,
    ): Uri? {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (template) {
            Template.DARK -> drawDark(c, spot, photos, cover, showCoord)
            Template.POLAROID -> drawPolaroid(c, spot, photos, cover, showCoord)
            Template.MAP -> drawMapBand(c, spot, photos, cover, showCoord)
        }
        return saveToGallery(ctx, bmp, template)
    }

    // ---------------- 模板一：深色满版 ----------------

    private fun drawDark(
        c: Canvas,
        spot: Spot,
        photos: List<Photo>,
        cover: Bitmap?,
        showCoord: Boolean,
    ) {
        // 背景：深色渐变，和 App 主色一致
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(0xFF141A21.toInt(), 0xFF0B0F14.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        // 封面图：占上半部分，圆角裁切
        val imgTop = 96f
        val imgH = 1080f
        val imgRect = RectF(MARGIN, imgTop, W - MARGIN, imgTop + imgH)
        if (cover != null) {
            drawCover(c, cover, imgRect, 28f)
        } else {
            // 没照片就画一块占位，避免卡片空一半
            val ph = Paint().apply { color = 0xFF1E262F.toInt() }
            c.drawRoundRect(imgRect, 28f, 28f, ph)
            val tp = Paint().apply {
                color = 0x66FFFFFF
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            c.drawText("这个打卡点还没照片", W / 2f, imgRect.centerY(), tp)
        }

        // 顶部小字：App 名
        val small = Paint().apply {
            color = 0x88FFFFFF.toInt()
            textSize = 30f
            isAntiAlias = true
        }
        c.drawText("打卡 · DAKA", MARGIN, 66f, small)

        val title = Paint().apply {
            color = Color.WHITE
            textSize = 76f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(ellipsize(spot.name, title, W - MARGIN * 2), MARGIN, imgTop + imgH + 110f, title)

        val meta = Paint().apply {
            color = 0xB3FFFFFF.toInt()
            textSize = 38f
            isAntiAlias = true
        }
        c.drawText(metaLine(spot), MARGIN, imgTop + imgH + 176f, meta)
        c.drawText(locText(spot, showCoord), MARGIN, imgTop + imgH + 236f, meta)

        // 备注：最多两行
        if (spot.note.isNotBlank()) {
            val note = Paint().apply {
                color = 0xE6FFFFFF.toInt()
                textSize = 36f
                isAntiAlias = true
            }
            drawMultiline(c, spot.note, MARGIN, imgTop + imgH + 306f, W - MARGIN * 2, 52f, note, 2)
        }

        drawFooter(c, photos, 0x66FFFFFF, 0x66FFFFFF)
    }

    // ---------------- 模板二：拍立得 ----------------

    private fun drawPolaroid(
        c: Canvas,
        spot: Spot,
        photos: List<Photo>,
        cover: Bitmap?,
        showCoord: Boolean,
    ) {
        // 米白底，微微暖一点，像相纸
        c.drawColor(0xFFF3EFE6.toInt())

        // 拍立得白卡：整体往里缩，底部留大片白（真实拍立得就是这样）
        val cardL = 54f
        val cardT = 120f
        val cardR = W - 54f
        val cardB = H - 300f
        val paper = Paint().apply {
            color = Color.WHITE
            setShadowLayer(18f, 0f, 6f, 0x33000000)
        }
        c.drawRoundRect(RectF(cardL, cardT, cardR, cardB), 8f, 8f, paper)

        val pad = 40f
        val imgRect = RectF(cardL + pad, cardT + pad, cardR - pad, cardT + pad + 980f)
        if (cover != null) {
            drawCover(c, cover, imgRect, 4f)
        } else {
            val ph = Paint().apply { color = 0xFFE4DED1.toInt() }
            c.drawRect(imgRect, ph)
            val tp = Paint().apply {
                color = 0x99000000.toInt()
                textSize = 36f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            c.drawText("还没照片", imgRect.centerX(), imgRect.centerY(), tp)
        }

        // 照片下方手写体感觉的标题（用默认字体斜体近似）
        val title = Paint().apply {
            color = 0xFF2B2B2B.toInt()
            textSize = 62f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(
            ellipsize(spot.name, title, cardR - cardL - pad * 2),
            cardL + pad, imgRect.bottom + 96f, title
        )

        val sub = Paint().apply {
            color = 0xFF6A6A6A.toInt()
            textSize = 32f
            isAntiAlias = true
        }
        c.drawText(metaLine(spot), cardL + pad, imgRect.bottom + 150f, sub)
        c.drawText(locText(spot, showCoord), cardL + pad, imgRect.bottom + 196f, sub)

        if (spot.note.isNotBlank()) {
            val note = Paint().apply {
                color = 0xFF444444.toInt()
                textSize = 30f
                isAntiAlias = true
            }
            drawMultiline(
                c, spot.note, cardL + pad, imgRect.bottom + 250f,
                cardR - cardL - pad * 2, 44f, note, 2
            )
        }

        // 底部：纸外面那圈米白上印小字
        val foot = Paint().apply {
            color = 0x992B2B2B.toInt()
            textSize = 30f
            isAntiAlias = true
        }
        if (photos.isNotEmpty()) {
            c.drawText("共 ${photos.size} 张照片", 54f, H - 180f, foot)
        }
        foot.textAlign = Paint.Align.RIGHT
        c.drawText("由「打卡」生成 · DAKA", W - 54f, H - 180f, foot)
    }

    // ---------------- 模板三：旅行条带 ----------------

    private fun drawMapBand(
        c: Canvas,
        spot: Spot,
        photos: List<Photo>,
        cover: Bitmap?,
        showCoord: Boolean,
    ) {
        c.drawColor(0xFF10161C.toInt())

        // 左侧竖向色带，颜色取这个打卡点自己的标记色——分享出去能看出它是哪一类
        val band = Paint().apply { color = spot.color }
        c.drawRect(0f, 0f, 26f, H.toFloat(), band)

        // 封面占右上，留出左边文字区
        val imgRect = RectF(150f, 110f, W - 54f, 1180f)
        if (cover != null) {
            drawCover(c, cover, imgRect, 20f)
        } else {
            val ph = Paint().apply { color = 0xFF1E262F.toInt() }
            c.drawRoundRect(imgRect, 20f, 20f, ph)
        }
        // 照片下方压一层渐变，让文字好读
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, imgRect.bottom - 220f, 0f, imgRect.bottom,
                intArrayOf(0x00000000, 0xFF10161C.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(imgRect, fade)

        val small = Paint().apply {
            color = 0x8AFFFFFF.toInt()
            textSize = 28f
            isAntiAlias = true
            letterSpacing = 0.12f
        }
        c.drawText("DAKA · 打卡", 150f, 74f, small)

        val title = Paint().apply {
            color = Color.WHITE
            textSize = 82f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(ellipsize(spot.name, title, W - 150f - 54f), 150f, 1320f, title)

        val meta = Paint().apply {
            color = 0xCCFFFFFF.toInt()
            textSize = 36f
            isAntiAlias = true
        }
        c.drawText(metaLine(spot), 150f, 1392f, meta)
        c.drawText(locText(spot, showCoord), 150f, 1448f, meta)

        if (spot.note.isNotBlank()) {
            val note = Paint().apply {
                color = 0x99FFFFFF.toInt()
                textSize = 32f
                isAntiAlias = true
            }
            drawMultiline(c, spot.note, 150f, 1516f, W - 150f - 54f, 46f, note, 3)
        }

        drawFooter(c, photos, 0x66FFFFFF, 0x66FFFFFF)
    }

    // ---------------- 公共部件 ----------------

    private fun metaLine(spot: Spot): String {
        val sdf = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)
        return buildString {
            append(sdf.format(Date(spot.createdAt)))
            if (spot.category.isNotEmpty()) append("  ·  ").append(spot.category)
            if (spot.rating > 0) append("  ·  ").append("★".repeat(spot.rating))
        }
    }

    /**
     * 位置行的文案。抽成 public 的纯函数是为了能在 JVM 单测里验脱敏逻辑——
     * 这是隐私相关的地方，不能只靠"上真机看一眼"。
     */
    fun locText(spot: Spot, showCoord: Boolean): String = if (showCoord) {
        String.format(Locale.US, "%.6f, %.6f", spot.lat, spot.lon)
    } else {
        String.format(Locale.US, "%.2f, %.2f 附近", spot.lat, spot.lon)
    }

    private fun drawFooter(c: Canvas, photos: List<Photo>, leftColor: Int, rightColor: Int) {
        val foot = Paint().apply {
            color = leftColor
            textSize = 32f
            isAntiAlias = true
        }
        if (photos.isNotEmpty()) {
            c.drawText("共 ${photos.size} 张照片", MARGIN, H - 96f, foot)
        }
        foot.color = rightColor
        foot.textAlign = Paint.Align.RIGHT
        c.drawText("由「打卡」生成", W - MARGIN, H - 96f, foot)
    }

    private fun drawCover(c: Canvas, src: Bitmap, dst: RectF, radius: Float) {
        c.save()
        val path = android.graphics.Path().apply {
            addRoundRect(dst, radius, radius, android.graphics.Path.Direction.CW)
        }
        c.clipPath(path)
        // 按 CENTER_CROP 语义铺满
        val scale = maxOf(dst.width() / src.width, dst.height() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = dst.left + (dst.width() - dw) / 2f
        val top = dst.top + (dst.height() - dh) / 2f
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), null)
        c.restore()
    }

    private fun drawMultiline(
        c: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        lineH: Float,
        paint: Paint,
        maxLines: Int,
    ) {
        var line = StringBuilder()
        var lineNo = 0
        var curY = y
        for (ch in text) {
            line.append(ch)
            if (paint.measureText(line.toString()) > maxWidth || ch == '\n') {
                val out = if (ch == '\n') line.toString().trimEnd('\n') else line.dropLast(1).toString()
                c.drawText(if (lineNo == maxLines - 1 && text.length > line.length) "$out…" else out, x, curY, paint)
                lineNo++
                if (lineNo >= maxLines) return
                curY += lineH
                line = StringBuilder(if (ch == '\n') "" else ch.toString())
            }
        }
        if (line.isNotEmpty() && lineNo < maxLines) c.drawText(line.toString(), x, curY, paint)
    }

    private fun ellipsize(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var out = s
        while (out.isNotEmpty() && p.measureText("$out…") > maxWidth) out = out.dropLast(1)
        return "$out…"
    }

    /** 存到相册 DCIM/打卡/卡片/，Android 10+ 免存储权限 */
    private fun saveToGallery(ctx: Context, bmp: Bitmap, template: Template): Uri? {
        val tag = when (template) {
            Template.DARK -> "dark"
            Template.POLAROID -> "polaroid"
            Template.MAP -> "map"
        }
        val name = "daka_card_${tag}_${System.currentTimeMillis()}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/打卡/卡片")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
                    }
                }
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "打卡/卡片",
                ).apply { mkdirs() }
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    /** 采样解码一张照片当封面，长边不超过 1080，避免 OOM */
    fun loadCover(ctx: Context, photo: Photo): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(Uri.parse(photo.uri))?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 1200) sample *= 2
            val real = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            ctx.contentResolver.openInputStream(Uri.parse(photo.uri))?.use {
                BitmapFactory.decodeStream(it, null, real)
            }
        } catch (e: Exception) {
            null
        }
    }
}
