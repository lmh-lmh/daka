package com.daka.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.daka.app.core.capture.SystemCamera
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.Photo
import com.daka.app.core.db.Spot
import com.daka.app.core.db.SpotDao
import com.daka.app.core.map.MarkerShape
import com.daka.app.core.photo.PhotoRepo
import com.daka.app.core.share.ShareCard
import com.daka.app.core.thumb.ThumbLoader
import com.daka.app.core.util.IconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡点详情页：照片墙 + 拍照 / 从相册导入 / 进编辑页。
 *
 * 这是"一个地点 = 一个打卡点，可以反复追加照片"的落地页面。
 */
class SpotDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SPOT_ID = "spot_id"
        private const val EDIT_REQUEST = 1001
    }

    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var camera: SystemCamera
    private lateinit var photoRepo: PhotoRepo
    private lateinit var spot: Spot

    private lateinit var ivIcon: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvStars: TextView
    private lateinit var tvNote: TextView
    private lateinit var tvPhotoCount: TextView
    private lateinit var gridPhotos: GridLayout
    private lateinit var tvEmpty: TextView

    private val photos = ArrayList<Photo>()
    private var changed = false

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> onCameraResult(r.resultCode) }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                photoRepo.attachFromGallery(spot.id, uri, spot.lon, spot.lat)
            }
            toast(res.message)
            if (res.ok) {
                changed = true
                reloadPhotos()
            }
        }
    }

    /** 编辑页返回时重新读一遍，保证改完立刻看到 */
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            changed = true
            if (r.data?.getBooleanExtra("deleted", false) == true) {
                finish()
                return@registerForActivityResult
            }
            reloadSpot()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spot_detail)

        db = DakaDb(this)
        dao = SpotDao(db)
        camera = SystemCamera(this)
        photoRepo = PhotoRepo(this, dao)

        val id = intent.getLongExtra(EXTRA_SPOT_ID, 0L)
        val found = dao.get(id)
        if (found == null) {
            toast("这个打卡点不存在了")
            finish()
            return
        }
        spot = found

        ivIcon = findViewById(R.id.ivIcon)
        tvTitle = findViewById(R.id.tvTitle)
        tvMeta = findViewById(R.id.tvMeta)
        tvStars = findViewById(R.id.tvStars)
        tvNote = findViewById(R.id.tvNote)
        tvPhotoCount = findViewById(R.id.tvPhotoCount)
        gridPhotos = findViewById(R.id.gridPhotos)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShoot).setOnClickListener { shoot() }
        findViewById<View>(R.id.btnPickPhoto).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<View>(R.id.btnShare).setOnClickListener { makeCard() }
        findViewById<View>(R.id.btnEdit).setOnClickListener {
            editLauncher.launch(
                Intent(this, SpotEditActivity::class.java)
                    .putExtra(SpotEditActivity.EXTRA_SPOT_ID, spot.id)
            )
        }

        bindHeader()
        reloadPhotos()
    }

    private fun reloadSpot() {
        val s = dao.get(spot.id) ?: run { finish(); return }
        spot = s
        bindHeader()
    }

    private fun bindHeader() {
        tvTitle.text = spot.name

        val px = dp(52)
        val icon = when {
            spot.iconFile.isNotEmpty() -> IconStore.loadIcon(this, spot.iconFile, px)
            spot.text.isNotEmpty() -> MarkerShape.render(spot.shape, spot.color, px, spot.text, "")
            else -> MarkerShape.render(spot.shape, spot.color, px, "", "")
        }
        ivIcon.setImageBitmap(icon)

        val created = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(spot.createdAt))
        tvMeta.text = buildString {
            append(String.format(Locale.US, "%.6f, %.6f", spot.lon, spot.lat))
            append(" · ")
            append(spot.category)
            if (spot.favorite) append(" · ★收藏")
        }

        tvStars.text = buildString {
            if (spot.rating > 0) {
                repeat(spot.rating) { append("★") }
                repeat(5 - spot.rating) { append("☆") }
            } else {
                append("未评分")
            }
            append("   ")
            append(created)
        }

        if (spot.note.isEmpty()) {
            tvNote.visibility = View.GONE
        } else {
            tvNote.visibility = View.VISIBLE
            tvNote.text = spot.note
        }
    }

    private fun reloadPhotos() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { dao.photosOf(spot.id) }
            photos.clear()
            photos.addAll(list)
            tvPhotoCount.text = if (photos.isEmpty()) "照片" else "照片 · ${photos.size} 张"
            tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
            buildGrid()
        }
    }

    /**
     * 照片墙：三列正方形。
     *
     * 为什么不用 GridLayout 的 weight 做三等分：GridLayout 的 LayoutParams 里
     * width=0 + columnSpec weight 在 Android 上并不会像 LinearLayout 那样平分，
     * 实测第一版里照片被拉成了一整条矮横条（真机截图确认）。
     * 这里改成按屏幕宽度直接算格子边长，行为确定、不依赖 GridLayout 的隐式规则。
     */
    private fun buildGrid() {
        gridPhotos.removeAllViews()
        val gap = dp(4)
        // GridLayout 自身左右 padding 是 12dp，列间距靠每个格子的 margin 撑开
        val usable = resources.displayMetrics.widthPixels - dp(24) - gap * 4
        val side = (usable / 3).coerceAtLeast(dp(80))
        for ((index, p) in photos.withIndex()) {
            val cell = ImageView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = side
                    height = side
                    setMargins(gap, gap, gap, gap)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ContextCompat.getDrawable(this@SpotDetailActivity, R.drawable.bg_card)
                setOnClickListener {
                    startActivity(
                        Intent(this@SpotDetailActivity, PhotoViewerActivity::class.java)
                            .putExtra(PhotoViewerActivity.EXTRA_SPOT_ID, spot.id)
                            .putExtra(PhotoViewerActivity.EXTRA_INDEX, index)
                    )
                }
            }
            gridPhotos.addView(cell)
            ThumbLoader.load(this, p, side) { bmp ->
                if (bmp != null && !isFinishing) cell.setImageBitmap(bmp)
            }
        }
    }

    // ---------------- 分享卡片 ----------------

    /**
     * 生成打卡卡片图并分享。
     *
     * 精确坐标默认不印在图上——分享出去等于把住址发到朋友圈。
     * 想印的话这里让用户明确选一次，不默认开。
     */
    private fun makeCard() {
        // 分两步：先挑版式，再问要不要印精确坐标。
        // 不合成一个列表（3×2=6 项）是因为用户看不懂"拍立得+显示坐标"这种组合名。
        val tpls = ShareCard.Template.values()
        val labels = tpls.map { "${it.label}　${it.desc}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选个卡片版式")
            .setItems(labels) { _, which ->
                askCoord(tpls[which])
            }
            .show()
    }

    private fun askCoord(template: ShareCard.Template) {
        val options = arrayOf("不显示精确位置（推荐）", "显示完整经纬度")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(template.label)
            .setItems(options) { _, which ->
                doMakeCard(showCoord = which == 1, template = template)
            }
            .show()
    }

    private fun doMakeCard(showCoord: Boolean, template: ShareCard.Template) {
        toast("正在生成…")
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val cover = photos.firstOrNull()?.let { ShareCard.loadCover(this@SpotDetailActivity, it) }
                ShareCard.build(this@SpotDetailActivity, spot, photos, cover, showCoord, template)
            }
            if (uri == null) {
                toast("卡片生成失败")
                return@launch
            }
            toast("已存到相册 DCIM/打卡/卡片/")
            shareCard(uri, showCoord)
        }
    }

    private fun shareCard(uri: Uri, showCoord: Boolean) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, buildString {
                append(spot.name)
                if (spot.category.isNotEmpty()) append(" · ").append(spot.category)
                if (!showCoord) append("\n（位置已模糊处理）")
            })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "分享到"))
        } catch (e: Exception) {
            toast("没有能接收图片的 App，图片已经存在相册里了")
        }
    }

    // ---------------- 拍照 ----------------

    private fun shoot() {
        if (!camera.hasCameraApp()) {
            toast("这台设备没找到相机 App，可以从相册导入照片")
            return
        }
        val intent = camera.newCaptureIntent(spot.name)
        if (intent == null) {
            toast("打不开相机，稍后再试")
            return
        }
        try {
            takePicture.launch(intent)
        } catch (e: Exception) {
            camera.abortPending()
            toast("打不开相机：${e.message ?: "未知原因"}")
        }
    }

    private fun onCameraResult(resultCode: Int) {
        if (resultCode != RESULT_OK) {
            // 用户取消：把 MediaStore 里那条空记录删掉，否则相册会多出一张 0 字节的图
            camera.abortPending()
            return
        }
        val shot = camera.validateAndConsume()
        if (shot == null) {
            toast("这张照片没存好，再拍一次")
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                photoRepo.attachFromCamera(spot.id, shot, spot.lon, spot.lat)
            }
            toast(res.message)
            if (res.ok) {
                changed = true
                reloadPhotos()
            }
        }
    }

    override fun onBackPressed() {
        if (changed) {
            setResult(RESULT_OK, Intent().putExtra("changed", true))
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        ThumbLoader.clearMem()
        db.close()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
