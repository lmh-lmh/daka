package com.daka.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义图标存储。
 *
 * 用户从相册选一张图当标记图标时，不能直接把相册的 content:// 存下来——
 * 那个 Uri 的读权限是临时的，App 重启后就失效了。所以必须把图复制到 App 私有目录，
 * 并且**先缩到合理尺寸**（不然一张 4000×3000 的图占 5MB，几十个图标就爆了）。
 */
object IconStore {

    private const val DIR = "icons"
    private const val MAX_PX = 192

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * 从相册 Uri 导入一个图标。
     * @return 文件名（不是完整路径），失败返回 null
     */
    fun import(ctx: Context, src: Uri): String? {
        return try {
            // 先探尺寸，再降采样解码
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / MAX_PX)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = ctx.contentResolver.openInputStream(src)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            // 缩到 MAX_PX 见方以内
            val scale = MAX_PX.toFloat() / maxOf(raw.width, raw.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else raw

            val name = "ic_${System.currentTimeMillis()}.png"
            FileOutputStream(File(dir(ctx), name)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (scaled !== raw) raw.recycle()
            name
        } catch (e: Exception) {
            null
        }
    }

    /** 按目标像素尺寸读回来，用于画标记 */
    fun loadIcon(ctx: Context, fileName: String, targetPx: Int): Bitmap? {
        return try {
            val f = File(dir(ctx), fileName)
            if (!f.exists()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0) return null
            val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / targetPx)
            val bitmap = BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /** 删除不再使用的图标文件 */
    fun delete(ctx: Context, fileName: String) {
        if (fileName.isEmpty()) return
        File(dir(ctx), fileName).delete()
    }
}
