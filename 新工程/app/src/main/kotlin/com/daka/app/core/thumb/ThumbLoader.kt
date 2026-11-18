package com.daka.app.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.daka.app.core.capture.PhotoMeta
import com.daka.app.core.db.Photo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图加载器。
 *
 * 为什么不用 Coil/Glide：
 * - Coil 的磁盘缓存官方限定只对 Internet 资源生效，本地文件不缓存（issue #2926 / #2367），
 *   而我们的照片全在本地，用它等于白拿一个内存缓存
 * - 我们已经在拍照时把 320px 缩略图预生成好了，这里只需要「读文件 + LruCache」
 * - 少一个依赖就少一份下载、少一份体积、少一类兼容问题
 *
 * 双层缓存：内存 LruCache（按字节算）+ 磁盘上已预生成的 jpg 文件。
 * 解码永远走采样解码，绝不原尺寸解码（一张 4000×3000 原图解成 ARGB_8888 是 48MB）。
 */
object ThumbLoader {

    private val maxMem = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(64 * 1024 * 1024)
    private val memCache = object : LruCache<String, Bitmap>(maxMem) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val pool = Executors.newFixedThreadPool(3)

    /**
     * 异步加载一张缩略图。主线程回调。
     *
     * @param reqPx 期望的长边像素（列表里 320 就够，格子里 240 就够）
     */
    fun load(ctx: Context, photo: Photo, reqPx: Int, onReady: (Bitmap?) -> Unit) {
        val key = "${photo.id}_$reqPx"
        memCache.get(key)?.let {
            if (!it.isRecycled) {
                onReady(it)
                return
            }
        }
        val cancelled = AtomicBoolean(false)
        pool.execute {
            val bmp = decode(ctx, photo, reqPx)
            if (bmp != null && !bmp.isRecycled) memCache.put(key, bmp)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!cancelled.get()) onReady(bmp)
            }
        }
    }

    /** 同步加载，调用方已在后台线程时用 */
    fun loadSync(ctx: Context, photo: Photo, reqPx: Int): Bitmap? {
        val key = "${photo.id}_$reqPx"
        memCache.get(key)?.let { if (!it.isRecycled) return it }
        val bmp = decode(ctx, photo, reqPx) ?: return null
        memCache.put(key, bmp)
        return bmp
    }

    private fun decode(ctx: Context, photo: Photo, reqPx: Int): Bitmap? {
        // 优先用预生成的缩略图文件
        if (photo.thumbFile.isNotEmpty()) {
            val f = PhotoMeta.thumbFile(ctx, photo.thumbFile)
            if (f.exists()) {
                val b = decodeSampled(f.absolutePath, reqPx)
                if (b != null) return b
            }
        }
        // 没缩略图（比如从相册导入的老照片），现解码一份
        return try {
            val uri = Uri.parse(photo.uri)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= reqPx) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampled(path: String, reqPx: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= reqPx) sample *= 2
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearMem() {
        memCache.evictAll()
    }
}
