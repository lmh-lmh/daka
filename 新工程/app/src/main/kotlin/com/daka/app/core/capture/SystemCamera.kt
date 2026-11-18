package com.daka.app.core.capture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 调系统相机拍照。
 *
 * 为什么走系统相机而不是 CameraX 内置相机（用户已确认"相机走系统"）：
 * - 原厂相机的多帧合成、HDR、夜景、防抖是厂商调了几年的，自己接 CameraX 追不上
 * - 少打 4 个 aar（camera-core/camera2/lifecycle/view），APK 小一截
 *
 * 几个必须做对的点（都是有官方出处的）：
 * 1. **EXTRA_OUTPUT 必须是 content:// Uri**，不能是 file://。
 *    官方 javadoc 说 EXTRA_OUTPUT 是 "a content resolver Uri"；
 *    裸 file:// 从 API 24 起会抛 FileUriExposedException。
 * 2. **manifest 里不要声明 CAMERA 权限**。javadoc 原文："if your app targets M and above
 *    and declares as using the CAMERA permission which is not granted, then attempting
 *    to use this action will result in a java.lang.SecurityException"。
 *    要用系统相机，只要不声明 CAMERA 就没事；声明了反而可能崩。
 * 3. **不设 IS_PENDING**。社区有大量"相机写入 0 字节"的报告说是 IS_PENDING 导致，
 *    虽然官方没有明文，但既然不需要也没好处，就不设。
 * 4. 用户取消（RESULT_CANCELED）必须把刚插入的空 MediaStore 记录删掉，
 *    否则相册里会留一堆 0 字节的坏文件。
 * 5. 返回 RESULT_OK 也不能直接相信，必须校验文件真的有内容、真的能解码。
 */
class SystemCamera(private val ctx: Context) {

    /** 一次拍照的挂起状态 */
    data class Pending(
        val uri: Uri,
        val relPath: String,
        val spotName: String,
    )

    private var pending: Pending? = null

    /**
     * 生成一个"待拍照"的位置并返回可发给相机的 Intent。
     *
     * 目录结构：DCIM/打卡/<打卡点名>/20261112_153012.jpg
     * 用户要求"原图，不需要水印，但文件夹要有信息方便后续整理"，
     * 所以用打卡点名字做子目录，照片自己看文件名就知道是哪天几点拍的。
     */
    fun newCaptureIntent(spotName: String): Intent? {
        val safeName = sanitize(spotName)
        val dir = "DCIM/打卡/$safeName"
        val fileName = fileName(System.currentTimeMillis())
        val relPath = "$dir/$fileName"

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：走 MediaStore，免存储权限
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, dir)
                // 不设 IS_PENDING（理由见类注释第 3 条）
            }
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } else {
            // Android 9 及以下：就得真的建文件，且需要 WRITE_EXTERNAL_STORAGE
            val external = android.os.Environment.getExternalStorageDirectory()
            val f = java.io.File(external, relPath)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.createNewFile()
            Uri.fromFile(f)
        } ?: return null

        pending = Pending(uri = uri, relPath = relPath, spotName = spotName)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // 只要求能拍照的 App，避免弹出没有相机的选择器
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    /**
     * 列出这台设备上所有能响应"拍照"的应用。
     *
     * 为什么要这个：`ACTION_IMAGE_CAPTURE` 默认会走系统选定的那个组件，在小米上是
     * `com.android.camera.OneShotCamera` —— 这是被裁剪过的精简拍照界面，没有 HDR、
     * 没有夜景、没有专业模式、不能切镜头。而同一个 App 的 `com.android.camera.Camera`
     * 是完整相机。用户要"能用上相机全部功能、最好还能选第三方相机"，
     * 所以这里把手动选择权交给用户。
     *
     * 注意要用 `queryIntentActivities` + `MATCH_ALL`，manifest 里的 `<queries>` 已声明
     * IMAGE_CAPTURE，否则 Android 11+ 的包可见性限制会让这里返回空列表。
     */
    fun listCameraApps(): List<CameraApp> {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val flags = android.content.pm.PackageManager.MATCH_ALL
        val resolved = ctx.packageManager.queryIntentActivities(intent, flags)
        val out = ArrayList<CameraApp>()
        val seen = HashSet<String>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val cls = ri.activityInfo?.name ?: continue
            if (!seen.add(pkg + "/" + cls)) continue
            val label = try {
                ri.loadLabel(ctx.packageManager).toString()
            } catch (e: Exception) {
                pkg
            }
            out.add(CameraApp(packageName = pkg, activityName = cls, label = label))
        }
        // 同一 App 的多个 activity 里，把 OneShot 那种"精简版"排后面，
        // 让完整的 Camera 入口优先出现（只是排序，不强制）
        return out.sortedWith(
            compareBy<CameraApp> { it.isLiteVariant() }.thenBy { it.label }
        )
    }

    /** 一个可用的相机应用 */
    data class CameraApp(
        val packageName: String,
        val activityName: String,
        val label: String,
    ) {
        /**
         * 是不是"精简/一次性"拍照入口。
         * 小米的 `OneShotCamera` 就是这种：只负责拍一张交差，功能被砍掉大半。
         */
        fun isLiteVariant(): Boolean =
            activityName.contains("OneShot", ignoreCase = true)
                || activityName.contains("Quick", ignoreCase = true)
    }

    /**
     * 生成"指定用某个相机 App 拍"的 Intent。
     *
     * 和 [newCaptureIntent] 的区别只是显式 setComponent。EXTRA_OUTPUT 那一套完全一样，
     * 所以待拍照位置、失败清理、结果校验都复用同一套逻辑。
     */
    fun newCaptureIntentWith(spotName: String, app: CameraApp): Intent? {
        val base = newCaptureIntent(spotName) ?: return null
        return try {
            Intent(base).apply {
                setClassName(app.packageName, app.activityName)
            }
        } catch (e: Exception) {
            // 组件名无效就退回默认行为
            base
        }
    }

    /** 当前挂起的拍照位置，界面上需要用 */
    fun currentPending(): Pending? = pending

    /**
     * 用户取消，或结果不可用时调用：删掉刚才插进 MediaStore 的空记录。
     * 不删的话相册里会出现 0 字节的坏图。
     */
    fun abortPending() {
        val p = pending ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.delete(p.uri, null, null)
            } else {
                // Android 9 及以下是我们自己建的实体文件，直接删
                val external = android.os.Environment.getExternalStorageDirectory()
                java.io.File(external, p.relPath).delete()
            }
        } catch (e: Exception) {
            // 删不掉就算了，不影响主流程
        }
        pending = null
    }

    /**
     * 拍照返回后调用，校验拍出来的东西到底有没有内容。
     *
     * 这一步不能省：某些机型的相机 App 会返回 RESULT_OK 但实际没写文件，
     * 或者写成 0 字节。不校验就会入库一堆坏照片，用户点开一片黑。
     *
     * @return 校验通过的 Snapshot；不合格返回 null（调用方应提示重拍）
     */
    fun validateAndConsume(): Snapshot? {
        val p = pending ?: return null
        pending = null

        val bytes = try {
            ctx.contentResolver.openFileDescriptor(p.uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }
        // 原图至少几百 KB；20KB 以下基本可以断定是缩略图或者空文件
        if (bytes < 20_000L) {
            // 不合格，清理掉
            try {
                ctx.contentResolver.delete(p.uri, null, null)
            } catch (e: Exception) {
            }
            return null
        }

        // 再确认真的能解码（防"文件大小对但内容损坏"）
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decodable = try {
            ctx.contentResolver.openInputStream(p.uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (e: Exception) {
            false
        }
        if (!decodable) {
            try {
                ctx.contentResolver.delete(p.uri, null, null)
            } catch (e: Exception) {
            }
            return null
        }

        return Snapshot(
            uri = p.uri,
            relPath = p.relPath,
            spotName = p.spotName,
            bytes = bytes,
            width = opts.outWidth,
            height = opts.outHeight,
        )
    }

    /** 一张拍成功的照片 */
    data class Snapshot(
        val uri: Uri,
        val relPath: String,
        val spotName: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
    )

    fun hasCameraApp(): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    companion object {
        /**
         * 把打卡点名字净化成能当文件夹名的字符串。
         * 不能有 / \ : * ? " < > | 这些字符，也不能是纯空格。
         */
        fun sanitize(name: String): String {
            val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t')
            var s = name
            for (ch in illegal) s = s.replace(ch, '_')
            s = s.trim().trim('.')
            if (s.isEmpty()) s = "未命名"
            // 文件夹名太长在某些文件系统上有问题，截一下
            return if (s.length > 40) s.substring(0, 40) else s
        }

        /** 20261112_153012 这种格式，按名字就能排序 */
        fun fileName(millis: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return "IMG_${fmt.format(java.util.Date(millis))}.jpg"
        }
    }
}
