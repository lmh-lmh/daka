package com.daka.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.Spot
import com.daka.app.core.db.SpotDao
import com.daka.app.core.map.MarkerShape
import com.daka.app.core.util.IconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡点列表：按分类筛选 + 按时间倒序。
 *
 * 这一步对应需求里的"海量标记点的组织问题"——地图上只能看空间分布，
 * 要找回"上个月在哪儿吃了什么"就得靠列表。
 */
class SpotListActivity : AppCompatActivity() {

    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var recycler: RecyclerView
    private lateinit var chipFilter: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvTitle: TextView
    private lateinit var etSearch: android.widget.EditText

    private val adapter = SpotAdapter()
    private var allSpots: List<Spot> = emptyList()
    private var filter = "全部"
    private var keyword = ""
    private var changed = false
    /** 列表排序：0=时间倒序 1=评分倒序 2=分类 */
    private var sortMode = 0

    private val detailLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            changed = true
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spot_list)

        db = DakaDb(this)
        dao = SpotDao(db)
        recycler = findViewById(R.id.recycler)
        chipFilter = findViewById(R.id.chipFilter)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvTitle = findViewById(R.id.tvTitle)
        etSearch = findViewById(R.id.etSearch)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                keyword = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        // 长按标题换排序：时间 / 评分 / 分类
        tvTitle.setOnLongClickListener {
            sortMode = (sortMode + 1) % 3
            val label = when (sortMode) {
                0 -> "按时间"
                1 -> "按评分"
                else -> "按分类"
            }
            android.widget.Toast.makeText(
                this@SpotListActivity, "排序：$label", android.widget.Toast.LENGTH_SHORT
            ).show()
            applyFilter()
            true
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { dao.all() }
            allSpots = list
            buildChips()
            applyFilter()
        }
    }

    private fun buildChips() {
        chipFilter.removeAllViews()
        val counts = allSpots.groupingBy { it.category }.eachCount()
        val names = ArrayList<String>()
        names.add("全部")
        names.add("收藏")
        names.addAll(counts.keys.sorted())
        for (name in names) {
            val n = when (name) {
                "全部" -> allSpots.size
                "收藏" -> allSpots.count { it.favorite }
                else -> counts[name] ?: 0
            }
            if (n == 0 && name != "全部") continue
            val tv = TextView(this).apply {
                text = if (name == "全部") "全部 ${n}" else "$name ${n}"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                ).apply { marginEnd = dp(8) }
                setTextColor(if (name == filter) Color.WHITE else 0xCCFFFFFF.toInt())
                background = if (name == filter) {
                    ContextCompat.getDrawable(this@SpotListActivity, R.drawable.bg_primary_pill)
                } else {
                    ContextCompat.getDrawable(this@SpotListActivity, R.drawable.bg_pill_outline)
                }
                setOnClickListener {
                    filter = name
                    buildChips()
                    applyFilter()
                }
            }
            chipFilter.addView(tv)
        }
    }

    private fun applyFilter() {
        var list = when (filter) {
            "全部" -> allSpots
            "收藏" -> allSpots.filter { it.favorite }
            else -> allSpots.filter { it.category == filter }
        }

        // 关键词：名字 / 备注 / 分类 / 标签 里任一命中就算（不区分大小写）
        if (keyword.isNotEmpty()) {
            val k = keyword.lowercase(Locale.CHINA)
            list = list.filter {
                it.name.lowercase(Locale.CHINA).contains(k) ||
                    it.note.lowercase(Locale.CHINA).contains(k) ||
                    it.category.lowercase(Locale.CHINA).contains(k)
            }
        }

        list = when (sortMode) {
            1 -> list.sortedWith(compareByDescending<Spot> { it.rating }.thenByDescending { it.createdAt })
            2 -> list.sortedWith(compareBy<Spot> { it.category }.thenByDescending { it.createdAt })
            else -> list.sortedByDescending { it.createdAt }
        }

        adapter.submit(list)
        tvTitle.text = buildString {
            append(if (filter == "全部") "我的打卡点" else filter)
            if (keyword.isNotEmpty()) append(" · 搜「$keyword」")
        }
        tvEmpty.text = if (keyword.isNotEmpty()) {
            "没有找到匹配「$keyword」的打卡点"
        } else {
            "还没有打卡点\n在地图上长按一下就能建"
        }
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 列表行：月份标题 或 打卡点。时间倒序时才插标题行 */
    private sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val spot: Spot) : Row()
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    inner class SpotAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = ArrayList<Row>()

        fun submit(list: List<Spot>) {
            rows.clear()
            if (sortMode == 0) {
                var lastMonth = ""
                for (s in list) {
                    val m = SimpleDateFormat("yyyy年M月", Locale.CHINA).format(Date(s.createdAt))
                    if (m != lastMonth) {
                        rows.add(Row.Header(m))
                        lastMonth = m
                    }
                    rows.add(Row.Item(s))
                }
            } else {
                list.forEach { rows.add(Row.Item(it)) }
            }
            notifyDataSetChanged()
        }

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val ivIcon: ImageView = root.findViewById(R.id.ivIcon)
            val tvName: TextView = root.findViewById(R.id.tvName)
            val tvSub: TextView = root.findViewById(R.id.tvSub)
            val tvBadge: TextView = root.findViewById(R.id.tvBadge)
        }

        inner class HVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(dp(16), dp(18), dp(16), dp(8))
                    setTextColor(0x8AFFFFFF.toInt())
                    textSize = 12f
                }
                HVH(tv)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_spot, parent, false)
                VH(v)
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is Row.Header) {
                (holder as HVH).tv.text = row.label
                return
            }
            val s = (row as Row.Item).spot
            val h = holder as VH
            val px = dp(44)
            val icon = when {
                s.iconFile.isNotEmpty() -> IconStore.loadIcon(this@SpotListActivity, s.iconFile, px)
                s.text.isNotEmpty() -> MarkerShape.render(s.shape, s.color, px, s.text, "")
                else -> MarkerShape.render(s.shape, s.color, px, "", "")
            }
            h.ivIcon.setImageBitmap(icon)

            h.tvName.text = s.name

            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            h.tvSub.text = buildString {
                append(sdf.format(Date(s.createdAt)))
                append(" · ")
                append(s.category)
                if (s.rating > 0) append(" · ").append("★".repeat(s.rating))
                if (s.favorite) append(" · ❤")
                append(" · ")
                append(String.format(Locale.US, "%.4f,%.4f", s.lat, s.lon))
            }

            h.tvBadge.text = when {
                s.photoCount > 0 -> "📷 ${s.photoCount}"
                else -> ""
            }

            h.root.setOnClickListener {
                detailLauncher.launch(
                    Intent(this@SpotListActivity, SpotDetailActivity::class.java)
                        .putExtra(SpotDetailActivity.EXTRA_SPOT_ID, s.id)
                )
            }
        }
    }

    override fun onBackPressed() {
        if (changed) setResult(RESULT_OK, Intent().putExtra("changed", true))
        super.onBackPressed()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
