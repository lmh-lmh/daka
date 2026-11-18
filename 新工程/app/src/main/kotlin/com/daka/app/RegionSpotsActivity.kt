package com.daka.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.Spot
import com.daka.app.core.db.SpotDao
import com.daka.app.core.map.NavShare
import com.daka.app.core.thumb.ThumbLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 某个行政区（区县/市/省）下的打卡点列表。
 *
 * 这是侧边"选到区县后再往下钻"的最后一层：把这一片的点逐个列出来，
 * 每个都能「定位过去」或「分享到导航 App」。
 *
 * 为什么单独开一个页面而不是塞在侧边面板里：
 * 1. 面板宽度只有 300dp，塞不下缩略图 + 两个按钮
 * 2. 用户在这一层要看的是"具体每个点"，和"选哪个行政区"是两种完全不同的任务
 */
class RegionSpotsActivity : AppCompatActivity() {

    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: Adapter

    private var allSpots: List<Spot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_region_spots)

        db = DakaDb(this)
        dao = SpotDao(db)

        tvTitle = findViewById(R.id.tvTitle)
        tvSub = findViewById(R.id.tvSub)
        tvEmpty = findViewById(R.id.tvEmpty)
        recycler = findViewById(R.id.recycler)

        val regionName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        tvTitle.text = regionName.ifEmpty { "打卡点" }
        tvSub.text = "点「定位」在地图上跳过去，点「导航」发给第三方导航"

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        adapter = Adapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        load()
    }

    private fun load() {
        val level = intent.getIntExtra(EXTRA_LEVEL, 2)
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    dao.spotsOfRegion(level, code)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            allSpots = list
            applyFilter("")
            tvSub.text = "${list.size} 个打卡点 · 点「定位」看位置，「导航」发到第三方地图"
        }
    }

    private fun applyFilter(q: String) {
        val filtered = if (q.isBlank()) allSpots else allSpots.filter {
            it.name.contains(q, ignoreCase = true) || it.note.contains(q, ignoreCase = true)
        }
        adapter.submit(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = if (allSpots.isEmpty()) "这个区县还没有打卡点" else "没有匹配的点"
    }

    /**
     * 定位到某个点：把坐标回传给主界面，由它移相机。
     *
     * 为什么用 setResult 而不是直接 startActivity(MainActivity)：
     * 后者会新建一个 Activity 实例，用户按返回会回到这个列表，
     * 再返回才是刚才的地图，体验很怪。回传结果让主界面原地移动相机最自然。
     */
    private fun locateTo(spot: Spot) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_LON, spot.lon)
                .putExtra(RESULT_LAT, spot.lat)
                .putExtra(RESULT_SPOT_ID, spot.id),
        )
        finish()
    }

    /** 分享到第三方导航：列出装了的 App 让用户选 */
    private fun shareToNav(spot: Spot) {
        val targets = NavShare.available(this)
        if (targets.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("没有可用的地图应用")
                .setMessage(
                    "这台设备上没找到能接收位置的地图 App。\n\n" +
                        "装一个高德/百度/腾讯地图，或者把坐标复制走：\n\n" +
                        "lat ${"%.6f".format(spot.lat)}\nlon ${"%.6f".format(spot.lon)}"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = targets.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("在哪里查看「${spot.name}」")
            .setItems(labels) { _, which ->
                val t = targets[which]
                val i = NavShare.intentFor(spot, t, this.packageName)
                if (i == null) {
                    toast("打不开 ${t.label}")
                    return@setItems
                }
                try {
                    startActivity(i)
                } catch (e: Exception) {
                    toast("打不开 ${t.label}：${e.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val data = ArrayList<Spot>()

        fun submit(list: List<Spot>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvMeta: TextView = v.findViewById(R.id.tvMeta)
            val btnLocate: TextView = v.findViewById(R.id.btnLocate)
            val btnNav: TextView = v.findViewById(R.id.btnNav)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_region_spot, parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = data[position]
            holder.tvName.text = s.name
            holder.tvMeta.text = buildString {
                append("%.5f, %.5f".format(s.lat, s.lon))
                if (s.photoCount > 0) append(" · ${s.photoCount} 张")
                if (s.category.isNotBlank()) append(" · ${s.category}")
            }
            holder.ivThumb.setImageDrawable(null)
            // ThumbLoader 只接受 Photo 对象，所以这里取该点最新那张照片。
            // 用 tag 做一次身份校验：列表复用时，异步回来的图可能属于别的行。
            holder.ivThumb.tag = s.id
            if (s.photoCount > 0) {
                val px = (56 * resources.displayMetrics.density).toInt()
                val spotId = s.id
                lifecycleScope.launch {
                    val photos = withContext(Dispatchers.IO) {
                        try {
                            dao.photosOf(spotId)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    val p = photos.firstOrNull() ?: return@launch
                    if (holder.ivThumb.tag != spotId) return@launch
                    ThumbLoader.load(this@RegionSpotsActivity, p, px) { bmp ->
                        if (bmp != null && holder.ivThumb.tag == spotId) {
                            holder.ivThumb.setImageBitmap(bmp)
                        }
                    }
                }
            }
            holder.btnLocate.setOnClickListener { locateTo(s) }
            holder.btnNav.setOnClickListener { shareToNav(s) }
            holder.itemView.setOnClickListener { locateTo(s) }
        }
    }

    companion object {
        const val EXTRA_CODE = "region_code"
        const val EXTRA_NAME = "region_name"
        const val EXTRA_LEVEL = "region_level"
        const val RESULT_LON = "result_lon"
        const val RESULT_LAT = "result_lat"
        const val RESULT_SPOT_ID = "result_spot_id"
    }
}
