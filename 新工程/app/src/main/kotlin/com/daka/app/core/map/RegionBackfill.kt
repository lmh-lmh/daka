package com.daka.app.core.map

import com.daka.app.core.db.SpotDao

/**
 * 老数据补算行政区归属。
 *
 * 背景：DB 从 v1 升到 v2 时，所有已存在的打卡点的 6 个行政区字段都是空串。
 * 这些点必须在后台批量补算，否则分层视图里它们全部落到"境外网格"。
 *
 * 为什么不阻塞启动：几千个点的最近中心匹配只要几十到几百毫秒，但为了万无一失
 * （万一用户有上万个点），这里分批处理、每批 200 个，批之间让出线程，
 * 界面完全不受影响。
 */
object RegionBackfill {

    private const val BATCH = 200

    /**
     * 补算所有缺失归属的点。
     *
     * 必须在后台线程调。会先确保 [AdminTree] 已加载。
     *
     * @param onProgress 已处理数 / 估算总数（都只在调用线程回调，调用方自己切主线程）
     * @return 实际更新了多少个点
     */
    fun run(dao: SpotDao, onProgress: (done: Int) -> Unit = {}): Int {
        if (!AdminTree.isLoaded()) return 0
        var updated = 0
        while (true) {
            val batch = dao.spotsWithoutRegion(BATCH)
            if (batch.isEmpty()) break
            for ((id, ll) in batch) {
                val a = AdminTree.assign(ll[0], ll[1])
                if (a == null) {
                    // 境外：留空串（分层视图会把它归到网格）。
                    // 这里不能什么都不写，否则下一轮 spotsWithoutRegion 还会把它捞出来，
                    // 变成死循环 —— 用一个哨兵值标记"已判定为境外"。
                    dao.updateRegion(id, OVERSEAS, "", "", "", "", "")
                } else {
                    dao.updateRegion(
                        id,
                        a.provinceCode, a.provinceName,
                        a.cityCode, a.cityName,
                        a.districtCode, a.districtName,
                    )
                }
                updated++
            }
            onProgress(updated)
        }
        return updated
    }

    /**
     * "已判定为境外"的哨兵值。
     *
     * 为什么用 `__abroad__` 而不是空串：空串是"还没算过"的标记，
     * 如果境外点也写空串，补算会无限循环。
     */
    const val OVERSEAS = "__abroad__"

    /** 这个点是否是境外（哨兵值或空） */
    fun isAbroad(provinceCode: String): Boolean =
        provinceCode.isEmpty() || provinceCode == OVERSEAS
}
