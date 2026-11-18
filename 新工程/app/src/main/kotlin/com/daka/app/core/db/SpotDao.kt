package com.daka.app.core.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * 打卡点和照片的读写。
 *
 * 调用约定：所有方法都是同步阻塞的，调用方自己放到 IO 线程（协程 Dispatchers.IO）。
 * 这样做是为了不引入一整套异步封装——数据量小（几千点），同步写反而更简单可靠。
 */
class SpotDao(private val helper: DakaDb) {

    private fun db(): SQLiteDatabase = helper.writableDatabase

    // ---------------- 打卡点 ----------------

    fun insert(s: Spot): Long {
        val cv = ContentValues().apply {
            put("lon", s.lon)
            put("lat", s.lat)
            put("name", s.name)
            put("note", s.note)
            put("category", s.category)
            put("rating", s.rating)
            put("favorite", if (s.favorite) 1 else 0)
            put("created_at", s.createdAt)
            put("updated_at", s.updatedAt)
            put("shape", s.shape)
            put("color", s.color)
            put("size", s.size)
            put("icon_file", s.iconFile)
            put("text", s.text)
            put("province_code", s.provinceCode)
            put("province_name", s.provinceName)
            put("city_code", s.cityCode)
            put("city_name", s.cityName)
            put("district_code", s.districtCode)
            put("district_name", s.districtName)
        }
        return db().insert("spot", null, cv)
    }

    fun update(s: Spot) {
        val cv = ContentValues().apply {
            put("lon", s.lon)
            put("lat", s.lat)
            put("name", s.name)
            put("note", s.note)
            put("category", s.category)
            put("rating", s.rating)
            put("favorite", if (s.favorite) 1 else 0)
            put("updated_at", System.currentTimeMillis())
            put("shape", s.shape)
            put("color", s.color)
            put("size", s.size)
            put("icon_file", s.iconFile)
            put("text", s.text)
            put("province_code", s.provinceCode)
            put("province_name", s.provinceName)
            put("city_code", s.cityCode)
            put("city_name", s.cityName)
            put("district_code", s.districtCode)
            put("district_name", s.districtName)
        }
        db().update("spot", cv, "id = ?", arrayOf(s.id.toString()))
    }

    /** 删除打卡点。照片记录随外键级联删除，但相册里的照片文件保留（避免误删不可恢复） */
    fun delete(id: Long) {
        db().delete("spot", "id = ?", arrayOf(id.toString()))
    }

    fun get(id: Long): Spot? {
        db().query("spot", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) readSpot(c) else null
        }
    }

    /**
     * 查视口内的点。
     *
     * 为什么按视口查而不是全量：阶段 2 要支持上万个点，全量塞 GeoJSON 会越来越慢。
     * 现在数据少也走同一条路径，避免将来改结构。
     */
    fun inBounds(
        lonMin: Double, latMin: Double, lonMax: Double, latMax: Double,
        limit: Int = 3000,
    ): List<Spot> {
        val sql = """
            SELECT s.*, 
                   (SELECT COUNT(*) FROM photo p WHERE p.spot_id = s.id) AS pc,
                   (SELECT p.rel_path FROM photo p WHERE p.spot_id = s.id ORDER BY p.taken_at DESC LIMIT 1) AS cover
            FROM spot s
            WHERE s.lon BETWEEN ? AND ? AND s.lat BETWEEN ? AND ?
            ORDER BY s.updated_at DESC
            LIMIT ?
        """.trimIndent()
        db().rawQuery(sql, arrayOf(lonMin.toString(), lonMax.toString(), latMin.toString(), latMax.toString(), limit.toString()))
            .use { c ->
                val out = ArrayList<Spot>(c.count)
                while (c.moveToNext()) {
                    val s = readSpot(c)
                    s.photoCount = c.getInt(c.getColumnIndexOrThrow("pc"))
                    s.coverPath = c.getString(c.getColumnIndexOrThrow("cover")) ?: ""
                    out.add(s)
                }
                return out
            }
    }

    /** 全部点，按更新时间倒序（列表视图用） */
    fun all(limit: Int = 5000): List<Spot> {
        val sql = """
            SELECT s.*, 
                   (SELECT COUNT(*) FROM photo p WHERE p.spot_id = s.id) AS pc,
                   (SELECT p.rel_path FROM photo p WHERE p.spot_id = s.id ORDER BY p.taken_at DESC LIMIT 1) AS cover
            FROM spot s
            ORDER BY s.updated_at DESC
            LIMIT ?
        """.trimIndent()
        db().rawQuery(sql, arrayOf(limit.toString())).use { c ->
            val out = ArrayList<Spot>(c.count)
            while (c.moveToNext()) {
                val s = readSpot(c)
                s.photoCount = c.getInt(c.getColumnIndexOrThrow("pc"))
                s.coverPath = c.getString(c.getColumnIndexOrThrow("cover")) ?: ""
                out.add(s)
            }
            return out
        }
    }

    fun count(): Int {
        db().rawQuery("SELECT COUNT(*) FROM spot", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun readSpot(c: Cursor): Spot = Spot(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
        category = c.getString(c.getColumnIndexOrThrow("category")) ?: "未分类",
        rating = c.getInt(c.getColumnIndexOrThrow("rating")),
        favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) != 0,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        shape = c.getString(c.getColumnIndexOrThrow("shape")) ?: "pin",
        color = c.getInt(c.getColumnIndexOrThrow("color")),
        size = c.getFloat(c.getColumnIndexOrThrow("size")),
        iconFile = c.getString(c.getColumnIndexOrThrow("icon_file")) ?: "",
        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
        provinceCode = c.getString(c.getColumnIndexOrThrow("province_code")) ?: "",
        provinceName = c.getString(c.getColumnIndexOrThrow("province_name")) ?: "",
        cityCode = c.getString(c.getColumnIndexOrThrow("city_code")) ?: "",
        cityName = c.getString(c.getColumnIndexOrThrow("city_name")) ?: "",
        districtCode = c.getString(c.getColumnIndexOrThrow("district_code")) ?: "",
        districtName = c.getString(c.getColumnIndexOrThrow("district_name")) ?: "",
    )

    // ---------------- 照片 ----------------

    fun insertPhoto(p: Photo): Long {
        val cv = ContentValues().apply {
            put("spot_id", p.spotId)
            put("uri", p.uri)
            put("rel_path", p.relPath)
            put("thumb_file", p.thumbFile)
            put("bytes", p.bytes)
            put("width", p.width)
            put("height", p.height)
            if (p.lon != null) put("lon", p.lon)
            if (p.lat != null) put("lat", p.lat)
            put("taken_at", p.takenAt)
            put("sha256", p.sha256)
        }
        return db().insert("photo", null, cv)
    }

    fun updatePhotoThumb(photoId: Long, thumbFile: String) {
        val cv = ContentValues().apply { put("thumb_file", thumbFile) }
        db().update("photo", cv, "id = ?", arrayOf(photoId.toString()))
    }

    fun deletePhotoRecord(photoId: Long) {
        db().delete("photo", "id = ?", arrayOf(photoId.toString()))
    }

    fun photosOf(spotId: Long): List<Photo> {
        db().query(
            "photo", null, "spot_id = ?", arrayOf(spotId.toString()),
            null, null, "taken_at DESC"
        ).use { c ->
            val out = ArrayList<Photo>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Photo(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        spotId = c.getLong(c.getColumnIndexOrThrow("spot_id")),
                        uri = c.getString(c.getColumnIndexOrThrow("uri")),
                        relPath = c.getString(c.getColumnIndexOrThrow("rel_path")),
                        thumbFile = c.getString(c.getColumnIndexOrThrow("thumb_file")) ?: "",
                        bytes = c.getLong(c.getColumnIndexOrThrow("bytes")),
                        width = c.getInt(c.getColumnIndexOrThrow("width")),
                        height = c.getInt(c.getColumnIndexOrThrow("height")),
                        lon = c.getDouble(c.getColumnIndexOrThrow("lon")).takeIf { !c.isNull(c.getColumnIndexOrThrow("lon")) },
                        lat = c.getDouble(c.getColumnIndexOrThrow("lat")).takeIf { !c.isNull(c.getColumnIndexOrThrow("lat")) },
                        takenAt = c.getLong(c.getColumnIndexOrThrow("taken_at")),
                        sha256 = c.getString(c.getColumnIndexOrThrow("sha256")) ?: "",
                    )
                )
            }
            return out
        }
    }

    fun photoCountOf(spotId: Long): Int {
        db().rawQuery("SELECT COUNT(*) FROM photo WHERE spot_id = ?", arrayOf(spotId.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ---------------- 分类 ----------------

    /** 全部分类名，按 sort 升序。category 表里预置了 5 条，用户后期可以加，这里不做缓存 */
    fun categories(): List<Pair<String, Int>> {
        db().query("category", arrayOf("name", "color"), null, null, null, null, "sort ASC, name ASC").use { c ->
            val out = ArrayList<Pair<String, Int>>(c.count)
            while (c.moveToNext()) {
                out.add(c.getString(0) to c.getInt(1))
            }
            return out
        }
    }

    /** 用过这个分类的打卡点数，列表页显示用 */
    fun countByCategory(): Map<String, Int> {
        val out = HashMap<String, Int>()
        db().rawQuery("SELECT category, COUNT(*) FROM spot GROUP BY category", null).use { c ->
            while (c.moveToNext()) out[c.getString(0) ?: "未分类"] = c.getInt(1)
        }
        return out
    }

    /** 按 sha256 查是否已存在，用于去重 */
    fun findPhotoBySha(sha: String): Photo? {
        if (sha.isEmpty()) return null
        db().query("photo", null, "sha256 = ?", arrayOf(sha), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return photosOf(c.getLong(c.getColumnIndexOrThrow("spot_id")))
                .firstOrNull { it.sha256 == sha }
        }
    }

    // ---------------- 分层聚合（v2 新增） ----------------

    /**
     * 一个行政区聚合结果。
     *
     * @param key 稳定标识：adcode，或境外网格 key（"g10.0_20_110"）
     * @param level 0=省 1=市 2=区县
     */
    data class RegionAgg(
        val key: String,
        val name: String,
        val spotCount: Int,
        val photoCount: Int,
        val coverRelPath: String,
    )

    /**
     * 按省聚合。
     *
     * 为什么不用视口过滤：全国最多 34 个省，一次全查出来毫秒级，切层时不用重复查库。
     * `province_code = ''` 的是境外点，由调用方走网格聚合。
     */
    fun aggByProvince(): List<RegionAgg> = aggBy(
        codeCol = "province_code", nameCol = "province_name",
        // 排除空串（未算过）和 __abroad__ 哨兵（已判定为境外）
        where = "s.province_code <> '' AND s.province_code <> '__abroad__'"
    )

    /** 按市聚合（限定某个省） */
    fun aggByCity(provinceCode: String): List<RegionAgg> = aggBy(
        codeCol = "city_code", nameCol = "city_name",
        where = "s.province_code = '" + esc(provinceCode) + "' AND s.city_code <> ''"
    )

    /** 按区县聚合（限定某个市） */
    fun aggByDistrict(cityCode: String): List<RegionAgg> = aggBy(
        codeCol = "district_code", nameCol = "district_name",
        where = "s.city_code = '" + esc(cityCode) + "' AND s.district_code <> ''"
    )

    /**
     * 还没有省级归属的点：未算过的（空串）和已判定为境外的（__abroad__）。
     *
     * 这两类都走网格聚合。**未算过的点绝不能隐藏**——否则 App 刚装上、
     * 行政区表还在后台加载时，用户会看到自己所有打卡点凭空消失。
     * 实测踩过：灌 1000 个测试点（没算归属）后，地图上一个都不显示。
     */
    fun abroadSpots(): List<Spot> =
        querySpots("s.province_code = '' OR s.province_code = '__abroad__'")

    /** 某个省下的所有点（下钻后画真实打卡点用） */
    fun spotsOfProvince(provinceCode: String, limit: Int = 5000): List<Spot> =
        querySpots("s.province_code = '" + esc(provinceCode) + "'", limit)

    /**
     * 按层级取某个行政区下的点。
     *
     * 为什么需要它：点侧边列表里的地区时，要飞到"该地区实际有点的位置"，
     * 而不是行政区几何中心 —— 实测高安市两者差 12 km，按中心飞过去是一片空地。
     */
    fun spotsOfRegion(level: Int, code: String): List<Spot> = when (level) {
        0 -> spotsOfProvince(code)
        1 -> spotsOfCity(code)
        else -> querySpots("s.district_code = '" + esc(code) + "'")
    }

    /** 某个市下的所有点 */
    fun spotsOfCity(cityCode: String, limit: Int = 5000): List<Spot> =
        querySpots("s.city_code = '" + esc(cityCode) + "'", limit)

    /**
     * 通用聚合。SQL 里用子查询算照片总数，不用 JOIN —— JOIN 点表与照片表会让
     * 行数变成"点×照片"，再 COUNT 得 DISTINCT，反而更慢且更容易写错。
     */
    private fun aggBy(codeCol: String, nameCol: String, where: String): List<RegionAgg> {
        val sql = """
            SELECT s.$codeCol AS k, MAX(s.$nameCol) AS nm,
                   COUNT(*) AS sc,
                   (SELECT COUNT(*) FROM photo p WHERE p.spot_id IN
                        (SELECT id FROM spot WHERE $codeCol = s.$codeCol)) AS pc,
                   (SELECT p.rel_path FROM photo p
                        JOIN spot ss ON ss.id = p.spot_id
                        WHERE ss.$codeCol = s.$codeCol
                        ORDER BY p.taken_at DESC LIMIT 1) AS cover
            FROM spot s
            WHERE $where
            GROUP BY s.$codeCol
            ORDER BY sc DESC
        """.trimIndent()
        val out = ArrayList<RegionAgg>()
        db().rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RegionAgg(
                        key = c.getString(0) ?: "",
                        name = c.getString(1) ?: "",
                        spotCount = c.getInt(2),
                        photoCount = c.getInt(3),
                        coverRelPath = c.getString(4) ?: "",
                    )
                )
            }
        }
        return out
    }

    /** 通用"按条件取点"，带照片数与封面 */
    private fun querySpots(where: String, limit: Int = 5000): List<Spot> {
        val sql = """
            SELECT s.*,
                   (SELECT COUNT(*) FROM photo p WHERE p.spot_id = s.id) AS pc,
                   (SELECT p.rel_path FROM photo p WHERE p.spot_id = s.id
                        ORDER BY p.taken_at DESC LIMIT 1) AS cover
            FROM spot s
            WHERE $where
            ORDER BY s.updated_at DESC
            LIMIT ?
        """.trimIndent()
        val out = ArrayList<Spot>()
        db().rawQuery(sql, arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                val s = readSpot(c)
                s.photoCount = c.getInt(c.getColumnIndexOrThrow("pc"))
                s.coverPath = c.getString(c.getColumnIndexOrThrow("cover")) ?: ""
                out.add(s)
            }
        }
        return out
    }

    /** 单值字符串的 SQL 转义（内部用，输入来自我们自己查出来的 adcode，不会有注入风险，但转义一下更稳） */
    private fun esc(v: String): String = v.replace("'", "''")

    // ---------------- 归属回填（v2 升级后用） ----------------

    /**
     * 还没算过行政归属的点（升级后批量补算用）。
     *
     * 注意只捞空串：__abroad__ 是"已判定为境外"的哨兵，不能再捞出来，
     * 否则补算会死循环（写哨兵 → 又被捞出来 → 又写哨兵…）。
     */
    fun spotsWithoutRegion(limit: Int = 2000): List<Pair<Long, DoubleArray>> {
        val out = ArrayList<Pair<Long, DoubleArray>>()
        db().rawQuery(
            "SELECT id, lon, lat FROM spot WHERE province_code = '' LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(c.getLong(0) to doubleArrayOf(c.getDouble(1), c.getDouble(2)))
            }
        }
        return out
    }

    /**
     * 把所有点的行政区归属清空，让它重新走 [spotsWithoutRegion] + 补算。
     *
     * 用途：归类算法升级后（比如从"最近中心"换成"多边形包含"），
     * 老数据是用旧算法算的，必须强制重算才不会继续错着。
     */
    fun resetAllRegions(): Int {
        val cv = ContentValues().apply {
            put("province_code", "")
            put("province_name", "")
            put("city_code", "")
            put("city_name", "")
            put("district_code", "")
            put("district_name", "")
        }
        return db().update("spot", cv, "province_code <> '' OR province_code = ''", null)
    }

    /** 写回一个点的行政归属 */
    fun updateRegion(
        id: Long,
        provinceCode: String, provinceName: String,
        cityCode: String, cityName: String,
        districtCode: String, districtName: String,
    ) {
        val cv = ContentValues().apply {
            put("province_code", provinceCode)
            put("province_name", provinceName)
            put("city_code", cityCode)
            put("city_name", cityName)
            put("district_code", districtCode)
            put("district_name", districtName)
        }
        db().update("spot", cv, "id = ?", arrayOf(id.toString()))
    }
}
