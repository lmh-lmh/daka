package com.daka.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.Photo
import com.daka.app.core.db.SpotDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 看原图。左右点屏幕边缘翻页。
 *
 * 这里走的是"原图"而不是缩略图：解码前先用 inJustDecodeBounds 探尺寸，
 * 再按屏幕大小算 inSampleSize——一张 4000×3000 全解码要 48MB，直接解会 OOM。
 */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SPOT_ID = "spot_id"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var ivPhoto: ImageView
    private lateinit var tvIndex: TextView
    private lateinit var tvInfo: TextView

    private var photos: List<Photo> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        db = DakaDb(this)
        dao = SpotDao(db)
        ivPhoto = findViewById(R.id.ivPhoto)
        tvIndex = findViewById(R.id.tvIndex)
        tvInfo = findViewById(R.id.tvInfo)

        val spotId = intent.getLongExtra(EXTRA_SPOT_ID, 0L)
        index = intent.getIntExtra(EXTRA_INDEX, 0)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.zonePrev).setOnClickListener { step(-1) }
        findViewById<View>(R.id.zoneNext).setOnClickListener { step(1) }

        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { dao.photosOf(spotId) }
            if (photos.isEmpty()) {
                finish()
                return@launch
            }
            index = index.coerceIn(0, photos.size - 1)
            show()
        }
    }

    private fun step(delta: Int) {
        if (photos.isEmpty()) return
        val next = index + delta
        if (next < 0 || next >= photos.size) {
            toast(if (delta < 0) "已经是第一张" else "已经是最后一张")
            return
        }
        index = next
        show()
    }

    private fun show() {
        val p = photos[index]
        tvIndex.text = "${index + 1} / ${photos.size}"

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        tvInfo.text = buildString {
            append(sdf.format(Date(p.takenAt)))
            if (p.width > 0 && p.height > 0) {
                append(" · ")
                append("${p.width}×${p.height}")
            }
            if (p.bytes > 0) {
                append(" · ")
                append(String.format(Locale.US, "%.1f MB", p.bytes / 1024.0 / 1024.0))
            }
            if (p.lon != null && p.lat != null) {
                append("\n")
                append(String.format(Locale.US, "拍摄位置 %.6f, %.6f", p.lon, p.lat))
            }
            if (p.relPath.isNotEmpty()) {
                append("\n")
                append(p.relPath)
            }
        }

        // 先放一张低清预览，避免大图解码时黑屏
        ivPhoto.setImageBitmap(null)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { loadFull(p) }
            if (!isFinishing) {
                if (bmp == null) {
                    toast("这张照片打不开了")
                } else {
                    ivPhoto.setImageBitmap(bmp)
                }
            }
        }
    }

    /** 按屏幕尺寸采样解码原图，不是缩略图 */
    private fun loadFull(p: Photo): Bitmap? {
        return try {
            val src: () -> java.io.InputStream? = {
                try {
                    contentResolver.openInputStream(Uri.parse(p.uri))
                } catch (e: Exception) {
                    // content:// 失效时退回按相对路径找公共相册文件
                    val f = File("/storage/emulated/0/${p.relPath}")
                    if (f.exists()) java.io.FileInputStream(f) else null
                }
            }
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            src()?.use { BitmapFactory.decodeStream(it, null, probe) }
            if (probe.outWidth <= 0) return null

            val target = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            var sample = 1
            while (probe.outWidth / (sample * 2) >= target && probe.outHeight / (sample * 2) >= target / 2) {
                sample *= 2
            }
            src()?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        ivPhoto.setImageBitmap(null)
        db.close()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
