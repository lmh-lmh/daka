package com.daka.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.Spot
import com.daka.app.core.db.SpotDao
import com.daka.app.core.map.MarkerShape
import com.daka.app.core.util.IconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 打卡点编辑页：名字 / 分类 / 备注 / 评分 / 收藏 / 标记样式。
 *
 * 样式在这里拆成三个独立维度（形状 10 种 × 任意颜色 × 大小）+ 文字 emoji + 自定义图片，
 * 对应需求里的"标记样式多样化、最好可自定义"。
 *
 * 入参：EXTRA_SPOT_ID（0 表示编辑传入的临时点，即先在 MapActivity 里建好再进来）。
 * 返回：RESULT_OK 时 setResult 带 resultChanged = true，让地图刷新。
 */
class SpotEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SPOT_ID = "spot_id"
    }

    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var spot: Spot

    private lateinit var preview: ImageView
    private lateinit var etName: EditText
    private lateinit var etNote: EditText
    private lateinit var chipCategory: LinearLayout
    private lateinit var starRow: LinearLayout
    private lateinit var cbFavorite: CheckBox
    private lateinit var gridShape: LinearLayout
    private lateinit var gridColor: LinearLayout
    private lateinit var gridEmoji: LinearLayout
    private lateinit var seekSize: SeekBar
    private lateinit var tvSize: TextView

    private var changed = false

    /** 从相册选自定义图标 */
    private val pickIcon = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { IconStore.import(this@SpotEditActivity, uri) }
            if (name == null) {
                toast("这张图读不出来，换一张试试")
                return@launch
            }
            // 换图标时把旧的删掉，避免私有目录里越堆越多
            if (spot.iconFile.isNotEmpty()) {
                withContext(Dispatchers.IO) { IconStore.delete(this@SpotEditActivity, spot.iconFile) }
            }
            spot.iconFile = name
            changed = true
            renderPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spot_edit)

        db = DakaDb(this)
        dao = SpotDao(db)
        val id = intent.getLongExtra(EXTRA_SPOT_ID, 0L)
        val found = if (id > 0) dao.get(id) else null
        if (found == null) {
            toast("这个打卡点不存在了")
            finish()
            return
        }
        spot = found

        preview = findViewById(R.id.preview)
        etName = findViewById(R.id.etName)
        etNote = findViewById(R.id.etNote)
        chipCategory = findViewById(R.id.chipCategory)
        starRow = findViewById(R.id.starRow)
        cbFavorite = findViewById(R.id.cbFavorite)
        gridShape = findViewById(R.id.gridShape)
        gridColor = findViewById(R.id.gridColor)
        gridEmoji = findViewById(R.id.gridEmoji)
        seekSize = findViewById(R.id.seekSize)
        tvSize = findViewById(R.id.tvSize)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.btnPickIcon).setOnClickListener { pickIcon.launch("image/*") }
        findViewById<View>(R.id.btnClearIcon).setOnClickListener {
            if (spot.iconFile.isEmpty()) {
                toast("现在没在用自定义图标")
            } else {
                val old = spot.iconFile
                spot.iconFile = ""
                changed = true
                lifecycleScope.launch(Dispatchers.IO) { IconStore.delete(this@SpotEditActivity, old) }
                renderPreview()
                buildEmojiGrid()
                buildShapeGrid()
            }
        }

        etName.setText(spot.name)
        etNote.setText(spot.note)
        cbFavorite.isChecked = spot.favorite

        seekSize.progress = ((spot.size - 0.6f) / 0.1f).toInt().coerceIn(0, 14)
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                spot.size = 0.6f + progress * 0.1f
                tvSize.text = String.format(Locale.US, "%.1f×", spot.size)
                if (fromUser) {
                    changed = true
                    renderPreview()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvSize.text = String.format(Locale.US, "%.1f×", spot.size)

        buildCategoryChips()
        buildStarRow()
        buildShapeGrid()
        buildColorGrid()
        buildEmojiGrid()
        renderPreview()
    }

    // ---------------- 分类 ----------------

    private fun buildCategoryChips() {
        chipCategory.removeAllViews()
        lifecycleScope.launch {
            val cats = withContext(Dispatchers.IO) { dao.categories() }
            val names = if (cats.isEmpty()) listOf("未分类" to Color.GRAY) else cats.map { it.first to it.second }
            for ((name, color) in names) {
                val tv = TextView(this@SpotEditActivity).apply {
                    text = name
                    setTextColor(if (name == spot.category) Color.WHITE else 0xCCFFFFFF.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                    ).apply { marginEnd = dp(8) }
                    background = if (name == spot.category) {
                        pillDrawable(color)
                    } else {
                        ContextCompat.getDrawable(this@SpotEditActivity, R.drawable.bg_pill_outline)
                    }
                    setOnClickListener {
                        spot.category = name
                        changed = true
                        buildCategoryChips()
                    }
                }
                chipCategory.addView(tv)
            }
        }
    }

    private fun pillDrawable(color: Int): android.graphics.drawable.Drawable? {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = dp(18).toFloat()
        d.setColor(color)
        return d
    }

    /** 调色盘里的小圆点。选中时加一圈白描边，让用户一眼看出当前用的是哪个色。 */
    private fun circleDrawable(color: Int, selected: Boolean): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(color)
        d.setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else 0x44FFFFFF)
        return d
    }

    // ---------------- 评分 ----------------

    private fun buildStarRow() {
        starRow.removeAllViews()
        for (i in 1..5) {
            val tv = TextView(this).apply {
                text = if (i <= spot.rating) "★" else "☆"
                setTextColor(0xFFFF8F00.toInt())
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                setOnClickListener {
                    // 点当前星级就取消评分，符合直觉
                    spot.rating = if (spot.rating == i) 0 else i
                    changed = true
                    buildStarRow()
                }
            }
            starRow.addView(tv)
        }
    }

    // ---------------- 形状 / 颜色 / emoji ----------------

    /**
     * 把一组子 View 铺成「每行 perRow 个」的网格。
     *
     * 为什么不用 GridLayout：实测它在 columnCount 固定时，每列宽度是按内容自适应算的，
     * 形状图标这种宽度不一致的内容会把行排歪（真机截图里 5 个形状排成了 4+2+2）。
     * 这里自己算每格宽度往外层 LinearLayout 里塞行，布局结果完全确定。
     */
    private fun fillGrid(container: LinearLayout, count: Int, perRow: Int, cell: (Int) -> View) {
        container.removeAllViews()
        if (count <= 0) return
        // 左右各 12dp padding 在 xml 里已经设了
        val usable = resources.displayMetrics.widthPixels - dp(24)
        val cellW = usable / perRow
        var row: LinearLayout? = null
        for (i in 0 until count) {
            if (i % perRow == 0) {
                row = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    orientation = LinearLayout.HORIZONTAL
                }
                container.addView(row)
            }
            val v = cell(i)
            v.layoutParams = LinearLayout.LayoutParams(cellW, cellW)
            row?.addView(v)
        }
    }

    private fun buildShapeGrid() {
        fillGrid(gridShape, MarkerShape.ALL.size, 5) { i ->
            val shape = MarkerShape.ALL[i]
            ImageView(this).apply {
                val box = dp(60)
                setImageBitmap(MarkerShape.render(shape, spot.color, box, "", ""))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = if (shape == spot.shape && spot.text.isEmpty() && spot.iconFile.isEmpty()) {
                    pillDrawable(0x33FFFFFF)
                } else {
                    null
                }
                setOnClickListener {
                    spot.shape = shape
                    // 选了形状就把文字和自定义图标让位，否则改了看不出效果，用户会困惑
                    spot.text = ""
                    if (spot.iconFile.isNotEmpty()) {
                        val old = spot.iconFile
                        spot.iconFile = ""
                        lifecycleScope.launch(Dispatchers.IO) { IconStore.delete(this@SpotEditActivity, old) }
                    }
                    changed = true
                    buildShapeGrid()
                    buildEmojiGrid()
                    renderPreview()
                }
            }
        }
    }

    private fun buildColorGrid() {
        fillGrid(gridColor, MarkerShape.PALETTE.size, 6) { i ->
            val color = MarkerShape.PALETTE[i]
            View(this).apply {
                val box = dp(40)
                val lp = LinearLayout.LayoutParams(box, box)
                val m = dp(7)
                lp.setMargins(m, m, m, m)
                layoutParams = lp
                background = circleDrawable(color, color == spot.color)
                setOnClickListener {
                    spot.color = color
                    changed = true
                    buildColorGrid()
                    buildShapeGrid()
                    renderPreview()
                }
            }
        }
    }

    private fun buildEmojiGrid() {
        fillGrid(gridEmoji, MarkerShape.EMOJI.size, 8) { i ->
            val e = MarkerShape.EMOJI[i]
            TextView(this).apply {
                text = e
                textSize = 22f
                gravity = Gravity.CENTER
                background = if (e == spot.text) pillDrawable(0x33FFFFFF) else null
                setOnClickListener {
                    // 再点一次取消，回到形状模式
                    spot.text = if (spot.text == e) "" else e
                    if (spot.text.isNotEmpty() && spot.iconFile.isNotEmpty()) {
                        val old = spot.iconFile
                        spot.iconFile = ""
                        lifecycleScope.launch(Dispatchers.IO) { IconStore.delete(this@SpotEditActivity, old) }
                    }
                    changed = true
                    buildEmojiGrid()
                    buildShapeGrid()
                    renderPreview()
                }
            }
        }
    }

    private fun renderPreview() {
        val px = dp(88)
        val bmp: Bitmap? = when {
            spot.iconFile.isNotEmpty() -> IconStore.loadIcon(this, spot.iconFile, px)
            spot.text.isNotEmpty() -> MarkerShape.render(spot.shape, spot.color, px, spot.text, "")
            else -> MarkerShape.render(spot.shape, spot.color, px, "", "")
        }
        preview.setImageBitmap(bmp ?: MarkerShape.render("pin", 0xFF2E7D32.toInt(), px, "", ""))
    }

    // ---------------- 保存 / 删除 ----------------

    private fun save() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("名字不能空着")
            return
        }
        spot.name = name
        spot.note = etNote.text.toString().trim()
        spot.favorite = cbFavorite.isChecked
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.update(spot) }
            toast("已保存")
            setResult(RESULT_OK, Intent().putExtra("changed", true))
            finish()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删掉这个打卡点？")
            .setMessage("照片文件会留在相册里，只是这里的记录没了。这个操作不能撤销。")
            .setNegativeButton("不删", null)
            .setPositiveButton("删掉") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.delete(spot.id) }
                    setResult(RESULT_OK, Intent().putExtra("changed", true).putExtra("deleted", true))
                    finish()
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
