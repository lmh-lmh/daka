package com.daka.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.app.core.achieve.Achievements
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.SpotDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 成就页。
 *
 * 按类别分组显示，解锁的排前面、锁着的排后面（锁着的不藏起来——用户要看到
 * "还有什么可以拿"，这是动力）。
 */
class AchievementsActivity : AppCompatActivity() {

    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var recycler: RecyclerView
    private lateinit var tvSummary: TextView
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_achievements)

        db = DakaDb(this)
        dao = SpotDao(db)
        recycler = findViewById(R.id.recycler)
        tvSummary = findViewById(R.id.tvSummary)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        adapter = Adapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                Achievements.evaluate(Achievements.stats(dao))
            }
            val unlocked = states.count { it.unlocked }
            tvSummary.text = "$unlocked / ${states.size}"
            adapter.submit(states)
        }
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    /** 扁平化的行：分类标题 + 成就项 */
    private sealed class Row {
        data class Header(val label: String, val unlocked: Int, val total: Int) : Row()
        data class Item(val state: Achievements.State) : Row()
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = ArrayList<Row>()

        fun submit(states: List<Achievements.State>) {
            rows.clear()
            // 按类别分组；组内解锁的在前
            for (kind in Achievements.Kind.values()) {
                val group = states.filter { it.def.kind == kind }
                if (group.isEmpty()) continue
                val unlocked = group.count { it.unlocked }
                rows.add(Row.Header(kind.label, unlocked, group.size))
                group.sortedWith(
                    compareByDescending<Achievements.State> { it.unlocked }
                        .thenByDescending { it.ratio }
                ).forEach { rows.add(Row.Item(it)) }
            }
            notifyDataSetChanged()
        }

        inner class HVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val tvIcon: TextView = root.findViewById(R.id.tvIcon)
            val tvTitle: TextView = root.findViewById(R.id.tvTitle)
            val tvDesc: TextView = root.findViewById(R.id.tvDesc)
            val progress: ProgressBar = root.findViewById(R.id.progress)
            val tvProgress: TextView = root.findViewById(R.id.tvProgress)
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(dp(16), dp(20), dp(16), dp(8))
                    setTextColor(0x8AFFFFFF.toInt())
                    textSize = 12f
                }
                HVH(tv)
            } else {
                VH(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_achievement, parent, false)
                )
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HVH).tv.text =
                    "${row.label}　${row.unlocked}/${row.total}"
                is Row.Item -> {
                    val h = holder as VH
                    val st = row.state
                    val d = st.def
                    h.tvIcon.text = d.icon
                    // 未解锁的图标变灰，一眼区分
                    h.tvIcon.alpha = if (st.unlocked) 1f else 0.35f
                    h.tvTitle.text = d.title
                    h.tvTitle.alpha = if (st.unlocked) 1f else 0.55f
                    h.tvDesc.text = d.desc
                    h.progress.progress = (st.ratio * 100).toInt()
                    h.progress.progressTintList = android.content.res.ColorStateList.valueOf(
                        if (st.unlocked) 0xFF43A047.toInt() else 0x66FFFFFF
                    )
                    h.tvProgress.text = if (st.unlocked) {
                        "已达成"
                    } else {
                        "${st.progress.coerceAtMost(d.target)}/${d.target}"
                    }
                    h.tvProgress.setTextColor(
                        if (st.unlocked) 0xFF43A047.toInt() else 0x8AFFFFFF.toInt()
                    )
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
