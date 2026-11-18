package com.daka.app.core.map

import com.daka.app.core.db.SpotDao

/**
 * 把数据库里的聚合结果转成「分层气泡」。
 *
 * 两层逻辑：
 * - **中国境内**：按 adcode 聚合，名字用行政区名（江西省 / 宜春市）
 * - **境外**：按经纬度网格聚合，名字用该格子里打卡点最多的那个点的名字
 *
 * 为什么境外要用"最多的那个点的名字"而不是"最近点的名字"：
 * 一个 10° 网格里可能有 20 个点，用 1 个点的名字会显得随机；用最多的那个，
 * 用户看到"巴黎"就明白这一片主要在巴黎打过卡。平票时取最近中心的那个。
 */
object RegionAggregator {

    /** 一个气泡。坐标是 WGS-84。 */
    data class Bubble(
        val key: String,
        val name: String,
        val lon: Double,
        val lat: Double,
        val spotCount: Int,
        val photoCount: Int,
        val coverRelPath: String,
        /** 0=省 1=市 2=区县 3=境外网格 */
        val level: Int,
    )

    /**
     * 省级（L0）。**只返回有点的省**——没去过的省完全不画，这是"不要太过繁琐"的核心。
     */
    fun provinces(dao: SpotDao): List<Bubble> {
        val rows = dao.aggByProvince()
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<Bubble>(rows.size)
        for (r in rows) {
            val unit = AdminTree.findProvince(r.key) ?: AdminTree.unitFromBounds(r.key) ?: continue
            out.add(
                Bubble(
                    key = r.key,
                    name = unit.name,
                    lon = unit.lon,
                    lat = unit.lat,
                    spotCount = r.spotCount,
                    photoCount = r.photoCount,
                    coverRelPath = r.coverRelPath,
                    level = MapLevel.L0_PROVINCE,
                )
            )
        }
        return out.sortedByDescending { it.spotCount }
    }

    /** 地市级（L1），限定在某个省 */
    fun cities(dao: SpotDao, provinceCode: String): List<Bubble> {
        val rows = dao.aggByCity(provinceCode)
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<Bubble>(rows.size)
        for (r in rows) {
            val unit = AdminTree.findUnit(r.key) ?: AdminTree.unitFromBounds(r.key) ?: continue
            out.add(
                Bubble(
                    key = r.key,
                    name = unit.name,
                    lon = unit.lon,
                    lat = unit.lat,
                    spotCount = r.spotCount,
                    photoCount = r.photoCount,
                    coverRelPath = r.coverRelPath,
                    level = MapLevel.L1_CITY,
                )
            )
        }
        return out.sortedByDescending { it.spotCount }
    }

    /** 区县级（L2），限定在某个市 */
    fun districts(dao: SpotDao, cityCode: String): List<Bubble> {
        val rows = dao.aggByDistrict(cityCode)
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<Bubble>(rows.size)
        for (r in rows) {
            // 区县的点表 districts.json 可能是空的，此时用边界数据自带的名字/中心点兜底，
            // 否则整层气泡会被 continue 丢掉（实测 L2 什么都不显示的根因）。
            val unit = AdminTree.findUnit(r.key) ?: AdminTree.unitFromBounds(r.key) ?: continue
            out.add(
                Bubble(
                    key = r.key,
                    name = unit.name,
                    lon = unit.lon,
                    lat = unit.lat,
                    spotCount = r.spotCount,
                    photoCount = r.photoCount,
                    coverRelPath = r.coverRelPath,
                    level = MapLevel.L2_DISTRICT,
                )
            )
        }
        return out.sortedByDescending { it.spotCount }
    }

    /**
     * 境外网格（L0/L1/L2 都可用）。
     *
     * 网格大小按层级变：L0 用 10°、L1 用 2°、L2 用 0.5°。
     * 名字取该格子里"出现最多的打卡点名"，平票取离格子中心最近的。
     */
    fun abroadGrid(dao: SpotDao, level: Int): List<Bubble> {
        val spots = dao.abroadSpots()
        if (spots.isEmpty()) return emptyList()
        val size = AdminTree.gridSizeFor(level)
        data class Acc(
            var count: Int = 0,
            var photos: Int = 0,
            var cover: String = "",
            val nameVotes: HashMap<String, Int> = HashMap(),
            var lonSum: Double = 0.0,
            var latSum: Double = 0.0,
        )

        val buckets = HashMap<String, Acc>()
        for (s in spots) {
            val key = AdminTree.gridKey(s.lon, s.lat, size)
            val a = buckets.getOrPut(key) { Acc() }
            a.count++
            a.photos += s.photoCount
            a.lonSum += s.lon
            a.latSum += s.lat
            if (a.cover.isEmpty() && s.coverPath.isNotEmpty()) a.cover = s.coverPath
            val nm = s.name.ifBlank { "未命名" }
            a.nameVotes[nm] = (a.nameVotes[nm] ?: 0) + 1
        }

        val out = ArrayList<Bubble>(buckets.size)
        for ((key, a) in buckets) {
            val (cx, cy) = AdminTree.gridCenter(key, size)
            // 出现最多的名字；平票时取字典序，保证结果稳定（不然每次刷新顺序会变）
            val bestName = a.nameVotes.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key ?: "未命名"
            out.add(
                Bubble(
                    key = key,
                    name = bestName,
                    lon = cx,
                    lat = cy,
                    spotCount = a.count,
                    photoCount = a.photos,
                    coverRelPath = a.cover,
                    level = 3,
                )
            )
        }
        return out.sortedByDescending { it.spotCount }
    }

    /**
     * 按当前层级给出该显示的气泡。
     *
     * @param centerProvince 当前视野中心落在哪个省（L1/L2 需要它来定范围）
     * @param centerCity     当前视野中心落在哪个市（L2 需要）
     */
    fun bubblesFor(
        dao: SpotDao,
        level: Int,
        centerProvince: AdminTree.Unit?,
        centerCity: AdminTree.Unit?,
    ): List<Bubble> {
        return when (level) {
            MapLevel.L0_PROVINCE -> provinces(dao) + abroadGrid(dao, MapLevel.L0_PROVINCE)
            MapLevel.L1_CITY -> {
                if (centerProvince == null) provinces(dao) + abroadGrid(dao, MapLevel.L1_CITY)
                else cities(dao, centerProvince.code) + abroadGrid(dao, MapLevel.L1_CITY)
            }
            MapLevel.L2_DISTRICT -> {
                if (centerCity == null) {
                    if (centerProvince == null) provinces(dao)
                    else cities(dao, centerProvince.code)
                } else {
                    districts(dao, centerCity.code) + abroadGrid(dao, MapLevel.L2_DISTRICT)
                }
            }
            else -> emptyList()   // L3 画真实打卡点，不用气泡
        }
    }
}
