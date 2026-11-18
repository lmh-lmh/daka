package com.daka.app.core.map

/**
 * 地区选择列表的数据源。
 *
 * 做什么：把"当前该显示哪些地区"整理成一份可搜索的清单，给侧边栏用。
 * 只列**有打卡点**的单元（没去过的地方不占位置），但搜索时允许搜到"没有打卡点"
 * 的单元 —— 用户可能想先跳过去看看，这是常见需求。
 *
 * 层级跟地图分层一致：
 *   全国视图 → 列省
 *   省内视图 → 列该省的市
 *   市内视图 → 列该市的区县
 */
object RegionPicker {

    /**
     * 一个可选项。
     *
     * @param count 该单元下的打卡点数（0 表示没去过，只在搜索结果里出现）
     * @param hasBounds 是否有边界数据（没有的话点了也能飞，只是画不出边界）
     */
    data class Item(
        val code: String,
        val name: String,
        val lon: Double,
        val lat: Double,
        val count: Int,
        val level: Int,
        val hasBounds: Boolean,
    )

    /**
     * 列出某一层可选的地区。
     *
     * @param level 0=省 1=市 2=区县
     * @param provinceCode 列市时用；为空表示还没钻进某个省
     * @param cityCode 列区县时用
     * @param counts 已有点数统计（key 是 adcode）
     */
    fun items(
        level: Int,
        provinceCode: String?,
        cityCode: String?,
        counts: Map<String, Int>,
    ): List<Item> {
        // 关键：列表实际列的是哪一级，由 drill 状态决定，不能只看地图 zoom。
        // 踩过的坑：用户直接从全国缩放到区县层（没经过列表下钻），level=2 但
        // province/city 都是空的，于是列的是省份、每个 Item 的 level 却标成 2，
        // 界面上江西省被标成"区县"。
        val actualLevel: Int
        val raw: List<AdminTree.Unit>
        when {
            !cityCode.isNullOrEmpty() -> {
                actualLevel = 2
                raw = AdminTree.districtsOf(cityCode)
            }
            !provinceCode.isNullOrEmpty() -> {
                actualLevel = 1
                raw = AdminTree.citiesOf(provinceCode)
            }
            else -> {
                actualLevel = 0
                raw = AdminTree.allProvinces()
            }
        }
        return raw.map { u ->
            Item(
                code = u.code,
                name = u.name,
                lon = u.lon,
                lat = u.lat,
                count = counts[u.code] ?: 0,
                level = actualLevel,
                hasBounds = true,
            )
        }.sortedWith(
            // 去过的排前面（按点数倒序），没去过的按名字排，方便找
            compareByDescending<Item> { it.count }.thenBy { it.name }
        )
    }

    /**
     * 模糊搜索。
     *
     * 匹配规则：去掉"省/市/区/县"这些后缀再比，这样输入"江西"能命中"江西省"，
     * 输入"宜春"能命中"宜春市"。同时支持 code 前缀匹配（输入 3609 能出宜春）。
     */
    fun search(items: List<Item>, query: String): List<Item> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { it.name.contains(q, ignoreCase = true) || it.code.startsWith(q) }
    }
}
