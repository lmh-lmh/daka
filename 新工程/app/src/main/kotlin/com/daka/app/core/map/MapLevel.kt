package com.daka.app.core.map

/**
 * 分层视图的层级定义，以及"地图 zoom → 层级"的换算。
 *
 * 四层：
 *   L0 全国    —— 只看有打卡点的省
 *   L1 省内    —— 看该省有打卡点的地市
 *   L2 市内    —— 看该市有打卡点的区县
 *   L3 打卡点  —— 回到原来的单点视图（聚类 + 图标 + 照片卡片）
 *
 * 阈值注意：地图 zoom 和瓦片层级差 1（见 ZoomGuard.TILE_ZOOM_OFFSET），
 * 而宜春的瓦片只到 z18、地图上限被压到 17。所以 L3 的起点定在 12 是安全的。
 */
object MapLevel {

    const val L0_PROVINCE = 0
    const val L1_CITY = 1
    const val L2_DISTRICT = 2
    const val L3_SPOT = 3

    /**
     * 层级阈值。
     *
     * z < 6   → 全国（看省）
     * 6 ~ 9   → 省内（看市）
     * 9 ~ 11  → 市内（看区县）
     * >= 11   → 打卡点
     *
     * L3 起点从 12 降到 11 的原因：实测 z11 时几个点只相距几百米，
     * 用气泡层显示会因为位置换算问题看不见（天级 bug），
     * 降到 11 让"已经放得比较大的视图"直接显示真实标记，看得见比分类准确更重要。
     */
    private const val Z_L1 = 6.0
    private const val Z_L2 = 9.0
    private const val Z_L3 = 11.0

    fun levelForZoom(zoom: Double): Int = when {
        zoom < Z_L1 -> L0_PROVINCE
        zoom < Z_L2 -> L1_CITY
        zoom < Z_L3 -> L2_DISTRICT
        else -> L3_SPOT
    }

    /** 每个层级的目标 zoom：下钻时用 */
    fun zoomForLevel(level: Int): Double = when (level) {
        L0_PROVINCE -> 4.0
        L1_CITY -> 7.5
        L2_DISTRICT -> 10.5
        else -> 14.0
    }

    fun label(level: Int): String = when (level) {
        L0_PROVINCE -> "全国"
        L1_CITY -> "省内"
        L2_DISTRICT -> "市内"
        else -> "打卡点"
    }
}
