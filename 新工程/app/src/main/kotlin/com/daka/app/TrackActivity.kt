package com.daka.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.TrackDao
import com.daka.app.core.track.Recorder
import com.daka.app.core.track.TrackGeo
import com.daka.app.core.track.TrackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 足迹列表：按天列出走过的路线，能开/关记录、能删某天。
 *
 * 关掉记录的方式有三种：这个页面上的按钮、通知栏上的「停止记录」、
 * 系统设置里强停 App。所以进页面时不能只信 [Recorder.running]，
 * 还要看服务是不是真的还在——但 Android 没给"查某个服务在不在跑"的干净 API，
 * 这里退一步：进页面时先按 Recorder 的状态显示，用户点了就以服务回调为准。
 */
class TrackActivity : AppCompatActivity() {

    private lateinit var db: DakaDb
    private lateinit var dao: TrackDao

    private lateinit var tvTitle: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnToggle: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DayAdapter

    private var days: List<TrackDao.Point> = emptyList()
    private val dayItems = ArrayList<DayItem>()

    private data class DayItem(
        val day: String,
        val count: Int,
        val meters: Double,
        val startAt: Long,
        val endAt: Long,
    )

    /** 轨迹权限：前台服务拿位置只要 FINE；Android 13+ 的通知权限只影响通知能不能看见 */
    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fine) {
            // 通知权限被拒也照样开记录：Android 13 不会因为没有通知权限就掐掉前台服务，
            // 只是用户看不到那条常驻通知而已。再弹一次只会陷入死循环。
            reallyStart()
        } else {
            toast("没有定位权限，记录不了足迹")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track)

        db = DakaDb(this)
        dao = TrackDao(db)

        tvTitle = findViewById(R.id.tvTitle)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnToggle = findViewById(R.id.btnToggle)
        recycler = findViewById(R.id.recycler)

        adapter = DayAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnToggle.setOnClickListener { onToggle() }

        refreshToggleLabel()
        reload()
    }

    override fun onResume() {
        super.onResume()
        refreshToggleLabel()
        reload()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun onToggle() {
        if (Recorder.running) {
            TrackService.stop(this)
            Recorder.running = false
            refreshToggleLabel()
            toast("已停止记录")
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine) {
            askPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }
        // Android 13+ 顺手要通知权限，被拒不影响记录（只是看不见常驻通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val n = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
            if (n != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                askPerms.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
                return
            }
        }
        reallyStart()
    }

    private fun reallyStart() {
        TrackService.start(this)
        Recorder.running = true
        refreshToggleLabel()
        toast("开始记录，走起来就有点了")
    }

    private fun refreshToggleLabel() {
        btnToggle.text = if (Recorder.running) "停止记录" else "开始记录"
        tvTitle.text = if (Recorder.running) "我的足迹 · 记录中" else "我的足迹"
    }

    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { loadDays() }
            dayItems.clear()
            dayItems.addAll(items)
            adapter.notifyDataSetChanged()
            tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

            val totalM = items.sumOf { it.meters }
            tvSummary.text = if (items.isEmpty()) {
                "还没有任何足迹"
            } else {
                "共 ${items.size} 天 · 总计 ${TrackGeo.prettyMeters(totalM)}"
            }
        }
    }

    private fun loadDays(): List<DayItem> {
        val out = ArrayList<DayItem>()
        for (day in dao.recentDays(60)) {
            val pts = dao.ofDay(day)
            if (pts.size < 2) continue
            out.add(
                DayItem(
                    day = day,
                    count = pts.size,
                    meters = TrackGeo.totalMeters(pts),
                    startAt = pts.first().recordedAt,
                    endAt = pts.last().recordedAt,
                )
            )
        }
        return out
    }

    private fun openDay(day: String) {
        startActivity(
            Intent(this, TrackDayActivity::class.java)
                .putExtra(TrackDayActivity.EXTRA_DAY, day)
        )
    }

    private fun askDelete(item: DayItem) {
        AlertDialog.Builder(this)
            .setTitle("删掉 ${TrackDao.prettyDay(item.day)} 的足迹？")
            .setMessage("这一天记录的 ${item.count} 个位置点会消失，打卡点和照片不受影响。删了不能撤销。")
            .setNegativeButton("不删", null)
            .setPositiveButton("删掉") { _, _ ->
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { dao.deleteDay(item.day) }
                    toast("已删掉 $n 个点")
                    reload()
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private inner class DayAdapter : RecyclerView.Adapter<DayAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val tvDay: TextView = root.findViewById(R.id.tvDay)
            val tvSub: TextView = root.findViewById(R.id.tvSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track_day, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = dayItems.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = dayItems[position]
            holder.tvDay.text = TrackDao.prettyDay(item.day)
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            val s = f.format(java.util.Date(item.startAt))
            val e = f.format(java.util.Date(item.endAt))
            holder.tvSub.text = "$s – $e · ${TrackGeo.prettyMeters(item.meters)} · ${item.count} 个点"

            holder.root.setOnClickListener { openDay(item.day) }
            holder.root.setOnLongClickListener {
                askDelete(item)
                true
            }
        }
    }
}
