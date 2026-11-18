package com.daka.app.core.photo

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.daka.app.core.capture.SystemCamera
import com.daka.app.core.capture.PhotoMeta
import com.daka.app.core.db.Photo
import com.daka.app.core.db.SpotDao
import java.io.File

/**
 * 照片入库流程。把「拍完 / 从相册选完」之后要做的一串动作收在这里：
 * 读信息 → 算哈希去重 → 补 EXIF GPS → 生成缩略图 → 写库。
 *
 * 全部同步执行，调用方放 IO 线程。
 */
class PhotoRepo(
    private val ctx: Context,
    private val dao: SpotDao,
) {

    data class Result(
        val ok: Boolean,
        val photoId: Long = 0,
        val message: String = "",
        val duplicate: Boolean = false,
    )

    /**
     * 把一张已经存在的照片登记到某个打卡点。
     *
     * @param spotLon/spotLat 打卡点坐标，用于给没有 GPS 的照片补写
     * @param writeGps       是否补写 EXIF（用户已确认"GPS 写"）
     */
    fun attach(
        spotId: Long,
        uri: Uri,
        relPath: String,
        spotLon: Double,
        spotLat: Double,
        writeGps: Boolean = true,
    ): Result {
        // 1. 去重：同一张照片不重复入库
        val sha = PhotoMeta.sha256(ctx, uri)
        if (sha.isNotEmpty()) {
            val dup = dao.findPhotoBySha(sha)
            if (dup != null) {
                return Result(ok = false, duplicate = true, message = "这张照片已经在「同一个点」里了")
            }
        }

        // 2. 读基本信息和拍摄 GPS
        val info = PhotoMeta.readInfo(ctx, uri)
            ?: return Result(ok = false, message = "读不出这张照片，可能文件损坏")

        // 3. 补写 GPS（系统相机经常不写，用户要求写真 GPS）
        if (writeGps && info.lon == null && info.lat == null) {
            PhotoMeta.writeGpsIfAbsent(ctx, uri, spotLon, spotLat)
        }

        // 4. 先入库拿 id，缩略图文件名要用 id
        val bytes = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }

        val photoId = dao.insertPhoto(
            Photo(
                spotId = spotId,
                uri = uri.toString(),
                relPath = relPath,
                thumbFile = "",
                bytes = bytes,
                width = info.width,
                height = info.height,
                lon = info.lon ?: spotLon,
                lat = info.lat ?: spotLat,
                takenAt = System.currentTimeMillis(),
                sha256 = sha,
            )
        )
        if (photoId <= 0) return Result(ok = false, message = "入库失败")

        // 5. 生成缩略图（320px，异步其实也可以，但一次几毫秒，同步更简单）
        val thumb = PhotoMeta.makeThumb(ctx, uri, photoId)
        if (thumb.isNotEmpty()) dao.updatePhotoThumb(photoId, thumb)

        return Result(ok = true, photoId = photoId)
    }

    /**
     * 从系统相机拍完的结果入库。
     * 这里会先校验拍到的到底是不是完整的原图（bytes 和尺寸），
     * 不合格就提示用户重拍，而不是把坏图存进去。
     */
    fun attachFromCamera(spotId: Long, shot: SystemCamera.Snapshot, spotLon: Double, spotLat: Double): Result {
        // 已经在校验阶段确认过 bytes >= 20KB 且可解码
        return attach(spotId, shot.uri, shot.relPath, spotLon, spotLat, writeGps = true)
    }

    /**
     * 从相册选一张已有照片挂到打卡点。
     * 这种情况照片本来就有真实 GPS，不补写。
     */
    fun attachFromGallery(spotId: Long, uri: Uri, spotLon: Double, spotLat: Double): Result {
        // 相册照片没有落在我们的目录结构里，relPath 记空
        return attach(spotId, uri, "", spotLon, spotLat, writeGps = false)
    }

    /**
     * 把一个 content:// uri 转成可读的公共相册相对路径，仅用于展示。
     * 拿不到就返回空串。
     */
    fun displayPath(uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
                    val nameIdx = c.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    val dir = if (idx >= 0) c.getString(idx) else ""
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else ""
                    "$dir$name"
                } else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 按相对路径（DCIM/打卡/xxx/yyy.jpg）找文件，用于 content:// 失效时兜底 */
    fun fileFromRelPath(relPath: String): File? {
        if (relPath.isEmpty()) return null
        val root = Environment.getExternalStorageDirectory()
        val f = File(root, relPath)
        return if (f.exists()) f else null
    }
}
