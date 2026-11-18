package com.daka.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.app.core.map.AdminTree
import com.daka.app.core.map.RegionPicker

/**
 * 侧边地区选择列表。
 *
 * 交互设计（对应"侧边放一个可开关的列表来选择/搜索各级别地区"）：
 * - 左上角按钮开关，面板从左滑出，点半透明遮罩或 ✕ 收起
 * - 面板内容跟着地图层级走：全国列省、省内列市、市内列区县
 * - 顶部有搜索框，输入地名或 adcode 都能命中
 * - 点某项：地图飞过去 + 自动钻到下一层
 * - 有打卡点的地区标绿点并显示数量，没去过的标灰点（仍可点，方便先去看看）
 */
class RegionPanel(
    private val activity: MainActivity,
    private val root: View,
) {

    private val panel: LinearLayout = root.findViewById(R.id.regionPanel)
    private val etSearch: EditText = root.findViewById(R.id.etSearch)
    private val tvCrumb: TextView = root.findViewById(R.id.tvPanelCrumb)
    private val tvTitle: TextView = root.findViewById(R.id.tvPanelTitle)
    private val tvEmpty: TextView = root.findViewById(R.id.tvPanelEmpty)
    private val btnBack: View = root.findViewById(R.id.btnPanelBack)
    private val btnHome: View = root.findViewById(R.id.btnPanelHome)
    private val recycler: RecyclerView = root.findViewById(R.id.recyclerRegion)
    private val scrim: View = root.findViewById(R.id.panelScrim)

    private val adapter = Adapter()
    private var allItems: List<RegionPicker.Item> = emptyList()

    /** 点某一项时回调：code / 经纬度 / 层级 */
    var onPick: ((RegionPicker.Item) -> Unit)? = null

    /** 面板收起时回调（用来恢复左上角的开关按钮） */
    var onHidden: (() -> Unit)? = null

    /** 点"返回上一级"时回调 */
    var onBack: (() -> Unit)? = null

    /** 点"回到全国"时回调 */
    var onHome: (() -> Unit)? = null

    /** 当前面板停在哪个层级，返回键靠它判断要不要显示 */
    private var currentLevel: Int = 0
    private var hasProvince: Boolean = false
    private var hasCity: Boolean = false

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        root.findViewById<View>(R.id.btnPanelClose).setOnClickListener { hide() }
        btnBack.setOnClickListener { onBack?.invoke() }
        btnHome.setOnClickListener { onHome?.invoke() }
        scrim.setOnClickListener { hide() }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    fun isVisible(): Boolean = panel.visibility == View.VISIBLE

    /**
     * 现在能不能返回上一级。
     *
     * 规则跟层级走：
     *   选区县（有市）→ 能退到市
     *   选城市（有省）→ 能退到省
     *   选省份 → 不能再退（上面就是全国）
     */
    fun canGoBack(): Boolean = when {
        currentLevel >= 2 -> hasCity
        currentLevel == 1 -> hasProvince
        else -> false
    }

    fun show() {
        panel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    fun hide() {
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        scrim.visibility = View.GONE
        onHidden?.invoke()
        // 收起时清掉搜索词，下次打开是干净状态
        etSearch.setText("")
        etSearch.clearFocus()
    }

    fun toggle() {
        if (isVisible()) hide() else show()
    }

    /**
     * 刷新面板内容。
     *
     * @param level 当前地图层级
     * @param provinceCode 已钻进的省（L1/L2 用）
     * @param cityCode 已钻进的市（L2 用）
     * @param counts 各地区打卡点数（key=adcode）
     */
    fun refresh(
        level: Int,
        provinceCode: String?,
        cityCode: String?,
        counts: Map<String, Int>,
    ) {
        // 面板标题说明当前在列什么，避免用户困惑"为什么只有这几个市"
        currentLevel = level
        hasProvince = !provinceCode.isNullOrEmpty()
        hasCity = !cityCode.isNullOrEmpty()

        // 标题和列表内容必须一致，所以这里按"实际列什么"来定，而不是按地图 zoom。
        // 踩过的坑：用户直接从全国缩放到区县层（没经过列表下钻），currentLevel=2
        // 但 drill 状态是空的，列表实际列的是省份，标题却写"选择城市"。
        val actual = when {
            hasCity -> 2
            hasProvince -> 1
            else -> 0
        }
        tvTitle.text = when (actual) {
            0 -> "选择省份"
            1 -> "选择城市"
            else -> "选择区县"
        }
        // 两个按钮分开判断：
        //  - "上一级"：只要还能往上一层就显示
        //  - "全国"：已经在全国层就没必要显示（点了也不会变化）
        btnBack.visibility = if (canGoBack()) View.VISIBLE else View.GONE
        val atCountry = actual == 0
        btnHome.visibility = if (atCountry) View.GONE else View.VISIBLE
        tvCrumb.text = buildString {
            append("全国")
            AdminTree.findProvince(provinceCode ?: "")?.let { append(" › ").append(it.shortName()) }
            AdminTree.findUnit(cityCode ?: "")?.let { append(" › ").append(it.shortName()) }
        }
        allItems = RegionPicker.items(actual, provinceCode, cityCode, counts)
        applyFilter(etSearch.text?.toString().orEmpty())
    }

    private fun applyFilter(q: String) {
        val list = RegionPicker.search(allItems, q)
        adapter.submit(list)
        tvEmpty.visibility = if (list.isEmpty() && allItems.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- 列表 ----------------

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private val data = ArrayList<RegionPicker.Item>()

        fun submit(list: List<RegionPicker.Item>) {
            data.clear()
            // 列表别太长：去过的优先已经排好，超过 300 条截断，靠着搜索找
            data.addAll(list.take(300))
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dot)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvSub: TextView = v.findViewById(R.id.tvSub)
            val tvCount: TextView = v.findViewById(R.id.tvCount)
            val tvArrow: TextView = v.findViewById(R.id.tvArrow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_region, parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = data[position]
            holder.tvName.text = it.name
            holder.dot.setBackgroundResource(
                if (it.count > 0) R.drawable.bg_dot_green else R.drawable.bg_dot_gray
            )
            holder.tvCount.text = if (it.count > 0) "${it.count}" else ""
            // 副标题：去过的显示层级关系，没去过的显示"还没去过"
            holder.tvSub.text = if (it.count > 0) {
                when (it.level) {
                    0 -> "省级"
                    1 -> "地级市"
                    // 区县级是最细一级，提示可以点进去看具体点位
                    else -> "${it.count} 个点 · 点进去看"
                }
            } else {
                "还没去过"
            }
            holder.tvName.alpha = if (it.count > 0) 1f else 0.6f
            // 区县级（最细一级）：箭头改成「查看」，点进去是该区县内的点列表
            holder.tvArrow.visibility = View.VISIBLE
            val item = it
            holder.itemView.setOnClickListener {
                onPick?.invoke(item)
                hide()
            }
        }
    }
}
