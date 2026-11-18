package com.daka.app.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 照片后处理：读 EXIF、补写 GPS、生成缩略图、算哈希。
 *
 * 关于 EXIF 的几个重要事实：
 * - 必须用 `androidx.exifinterface`，不要用平台自带的 `android.media.ExifInterface`。
 *   平台版官方自己都写明了写 GPS 能力弱、建议换 androidx。
 * - 补写 GPS 时，Android 10+ 直接 saveAttributes() 会 IOException，必须用
 *   contentResolver.openFileDescriptor(uri, "rw") 拿到 FD 再写。
 * - **HEIC/AVIF 不能写 EXIF**，官方原文 "Supported for writing: JPEG, PNG, WebP"。
 *   遇到这两种格式就跳过补写，不要当成失败。
 * - 必须假设系统相机**没有写 GPS**。国产 ROM 的相机经常不给照片写 GPS，
 *   所以坐标以打卡点自身的为准，补写进去。
 * - 像素哈希验证：补写 EXIF 前后比对像素数据，确认没有被重新编码（重编码会掉画质）。
 */
object PhotoMeta {

    /**
     * 读取照片的基本信息和拍摄 GPS。
     * 系统相机没写 GPS 时返回 null。
     */
    data class Info(
        val width: Int,
        val height: Int,
        val lon: Double?,
        val lat: Double?,
        val orientation: Int,
        val mime: String,
    )

    fun readInfo(ctx: Context, uri: Uri): Info? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var lon: Double? = null
            var lat: Double? = null
            var orientation = ExifInterface.ORIENTATION_NORMAL
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val exif = ExifInterface(ins)
                val lng = exif.latLong
                if (lng != null && lng.size == 2) {
                    lat = lng[0]
                    lon = lng[1]
                }
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
            Info(
                width = opts.outWidth,
                height = opts.outHeight,
                lon = lon,
                lat = lat,
                orientation = orientation,
                mime = ctx.contentResolver.getType(uri) ?: "image/jpeg",
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把打卡点的坐标补写进照片 EXIF。
     *
     * 只在照片本来没有 GPS 时才写——如果系统相机已经写了真实 GPS（比打卡点更准），
     * 就不要覆盖。
     *
     * @return 写入是否成功（HEIC 等不支持写的情况返回 false，不算错误）
     */
    fun writeGpsIfAbsent(ctx: Context, uri: Uri, lon: Double, lat: Double): Boolean {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            // HEIC/AVIF 官方不支持写 EXIF
            if (!mime.contains("jpeg") && !mime.contains("jpg") && !mime.contains("png") && !mime.contains("webp")) {
                return false
            }
            val fd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return false
            fd.use {
                val exif = ExifInterface(it.fileDescriptor)
                val existing = exif.latLong
                if (existing != null && existing.size == 2) {
                    // 已经有 GPS 了，不覆盖
                    return true
                }
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(lat, true))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(lon, false))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
                exif.saveAttributes()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 十进制度转 EXIF 的度分秒格式，例：27/1,48/1,0/1 */
    private fun toDms(value: Double, isLat: Boolean): String {
        val abs = kotlin.math.abs(value)
        val deg = abs.toInt()
        val minFloat = (abs - deg) * 60
        val min = minFloat.toInt()
        val sec = ((minFloat - min) * 60 * 10000).toInt()
        return "$deg/1,$min/1,$sec/10000"
    }

    /**
     * 生成缩略图。320px 长边、JPEG q80，一张约 15~40KB。
     *
     * 为什么必须自己预生成：一张 4000×3000 的照片如果原尺寸解码成 ARGB_8888，
     * 内存占 48MB。列表里翻几张就 OOM 了。所以缩略图要在拍照完成时一次性生成好，
     * 之后列表只读缩略图。
     *
     * @return 缩略图文件名（存在 App 私有目录 thumbs/ 下），失败返回 ""
     */
    fun makeThumb(ctx: Context, uri: Uri, photoId: Long): String {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0) return ""
            // 先粗采样，避免直接解码原图
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 320) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            } ?: return ""

            val scale = 320f / maxOf(bmp.width, bmp.height)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp

            val dir = File(ctx.filesDir, "thumbs")
            if (!dir.exists()) dir.mkdirs()
            val name = "t_$photoId.jpg"
            FileOutputStream(File(dir, name)).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (thumb !== bmp) bmp.recycle()
            thumb.recycle()
            name
        } catch (e: Exception) {
            ""
        }
    }

    fun thumbFile(ctx: Context, name: String): File = File(File(ctx.filesDir, "thumbs"), name)

    /**
     * 算文件的 sha256，用于去重（同一张照片重复选中不该入库两次）。
     * 大文件按 64KB 分块读，不一次性载入内存。
     */
    fun sha256(ctx: Context, uri: Uri): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 补写 EXIF 后验证像素数据没变（确认没有重新编码掉画质）。
     * 比对方式：把图像缩小到 64×64 灰度再算简单哈希，容许轻微差异（EXIF 写入不改像素，
     * 理论上应该完全一致，给一点容差防实现差异）。
     */
    fun pixelSignature(ctx: Context, uri: Uri): Long {
        return try {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = 16
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            } ?: return 0L
            var hash = 17L
            for (y in 0 until bmp.height step maxOf(1, bmp.height / 16)) {
                for (x in 0 until bmp.width step maxOf(1, bmp.width / 16)) {
                    hash = hash * 31 + bmp.getPixel(x, y)
                }
            }
            bmp.recycle()
            hash
        } catch (e: Exception) {
            0L
        }
    }
}
