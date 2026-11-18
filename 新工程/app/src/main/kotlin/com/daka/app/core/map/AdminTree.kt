package com.daka.app.core.map

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 行政区划树 + 坐标转换。
 *
 * 数据来源：阿里云 DataV `areas_v3`，构建期用 tools/build_admin_assets.py 处理成三个精简 JSON
 * 放进 assets/admin/。构建期做了三件事：丢掉多边形几何（只留中心点）、GCJ-02 转 WGS-84、
 * 过滤九段线那条脏数据。
 *
 * **为什么必须转坐标**：DataV 的 center 是 GCJ-02 火星坐标。实测宜春市给的是
 * 114.391 而实际 WGS-84 是 114.417，差约 2.6 km —— 不转的话地图上的图标会整体偏移。
 *
 * **归类策略是"最近中心"，不是"点在多边形内"**：全国行政区边界有几十 MB，
 * 塞进包里和实时做 point-in-polygon 都不划算。用户要的是"大致看出去了哪些地方"，
 * 按最近中心归类足够用；代价是省界、飞地附近的点可能归到邻省，这一点已知并接受。
 */
object AdminTree {

    /** 一个行政区单元。坐标已转成 WGS-84。 */
    data class Unit(
        val code: String,
        val name: String,
        val lon: Double,
        val lat: Double,
        val parent: String = "",
        val children: Int = 0,
    ) {
        /** 用于显示的短名：去掉"省/市/自治区"这类后缀，省气泡上字更少 */
        fun shortName(): String = name
            .removeSuffix("维吾尔自治区")
            .removeSuffix("壮族自治区")
            .removeSuffix("回族自治区")
            .removeSuffix("自治区")
            .removeSuffix("特别行政区")
            .removeSuffix("省")
            .removeSuffix("市")
            .ifEmpty { name }
    }

    @Volatile
    private var loaded = false

    private var provinces: List<Unit> = emptyList()

    /**
     * 省级边界（用于 point-in-polygon 精确定省）。
     *
     * 为什么需要它：只靠"最近中心"会在省界附近判错。实测反例——丰城（属宜春市）
     * 的打卡点 115.406,28.232 到新余市中心 66 km、到宜春市中心 111 km，
     * 按最近中心会判给新余市，用户在自家门口打卡却显示到邻市。
     *
     * 边界数据 402 KB、约 2 万个点，一次性载入后判点只需遍历一次环，
     * 单点耗时微秒级，几千个点也能秒算完。
     */
    private var provinceBounds: List<Bound> = emptyList()
    /** 市级边界，同样用于精确定市（丰城错判新余就是靠它修的） */
    private var cityBounds: List<Bound> = emptyList()
    /** 区县边界 */
    private var districtBounds: List<Bound> = emptyList()

    /**
     * 一个行政单元的边界：可能多块（海南、舟山群岛等）。
     *
     * 同时带着 center / parent 有两个用处：
     * 1. 边界命中后能直接构造出 Unit，不用再去点表里查（区县点表是空的）
     * 2. 分层视图的"当前在哪个省/市"也能靠它算
     */
    class Bound(
        val code: String,
        val name: String,
        val rings: List<FloatArray>,
        val lon: Double,
        val lat: Double,
        val parent: String,
    ) {
        fun toUnit(): Unit = Unit(
            code = code, name = name, lon = lon, lat = lat,
            parent = parent, children = 0,
        )
    }
    /** parent(省级 code) -> 地市列表 */
    private var citiesByProvince: Map<String, List<Unit>> = emptyMap()
    /** parent(地市 code) -> 区县列表 */
    private var districtsByCity: Map<String, List<Unit>> = emptyMap()

    private const val ASSET_DIR = "admin"

    /**
     * 载入 assets。**必须在后台线程调**（要读几百 KB JSON 并解析）。
     * 重复调用是幂等的，内部有 loaded 标记。
     */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        provinces = readUnits(ctx, "provinces.json")
        provinceBounds = readBounds(ctx, "province_bounds.json")
        cityBounds = readBounds(ctx, "city_bounds.json")
        districtBounds = readBounds(ctx, "district_bounds.json")
        val cities = readUnits(ctx, "cities.json")
        val districts = readUnits(ctx, "districts.json")
        citiesByProvince = cities.groupBy { it.parent }
        districtsByCity = districts.groupBy { it.parent }
        loaded = true
    }

    fun isLoaded(): Boolean = loaded

    /**
     * 读省级边界。
     *
     * 存成 FloatArray（[lon0,lat0,lon1,lat1,...]）而不是 List<Pair>：
     * 2 万个点在 Android 上用对象包装会额外占几 MB 堆，float 数组紧凑得多。
     */
    private fun readBounds(ctx: Context, fileName: String): List<Bound> {
        return try {
            val text = ctx.assets.open("$ASSET_DIR/$fileName")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.optJSONArray("units") ?: return emptyList()
            val out = ArrayList<Bound>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optString("code")
                val name = o.optString("name")
                if (code.isEmpty()) continue
                val c = o.optJSONArray("center")
                val clon = if (c != null && c.length() >= 2) c.getDouble(0) else 0.0
                val clat = if (c != null && c.length() >= 2) c.getDouble(1) else 0.0
                val parent = o.optString("parent")
                val polys = o.optJSONArray("polys") ?: continue
                val rings = ArrayList<FloatArray>(polys.length())
                for (pi in 0 until polys.length()) {
                    val ring = polys.optJSONArray(pi) ?: continue
                    if (ring.length() < 3) continue
                    val flat = FloatArray(ring.length() * 2)
                    var k = 0
                    for (ri in 0 until ring.length()) {
                        val pt = ring.optJSONArray(ri) ?: continue
                        if (pt.length() < 2) continue
                        flat[k++] = pt.getDouble(0).toFloat()
                        flat[k++] = pt.getDouble(1).toFloat()
                    }
                    rings.add(flat)
                }
                if (rings.isNotEmpty()) out.add(Bound(code, name, rings, clon, clat, parent))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readUnits(ctx: Context, fileName: String): List<Unit> {
        return try {
            val text = ctx.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.optJSONArray("units") ?: return emptyList()
            val out = ArrayList<Unit>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optJSONArray("center") ?: continue
                if (c.length() < 2) continue
                val code = o.optString("code")
                val name = o.optString("name")
                if (code.isEmpty() || name.isEmpty()) continue
                out.add(
                    Unit(
                        code = code,
                        name = name,
                        lon = c.getDouble(0),
                        lat = c.getDouble(1),
                        parent = o.optString("parent"),
                        children = o.optInt("children", 0),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- 查询 ----------------

    fun allProvinces(): List<Unit> = provinces

    /**
     * 某个省下的地市。
     *
     * 直辖市例外：北京/上海的省级文件里直接是区县，没有"地市"这一级。
     * 这时把区县当作"下一级"返回，否则点北京会进到一个空列表 —— 用户看到的就是
     * "点了北京市没有任何反应"。判据：该省在 cities 里没有任何以 00 结尾的子单元。
     */
    fun citiesOf(provinceCode: String): List<Unit> {
        val direct = citiesByProvince[provinceCode].orEmpty()
        val real = direct.filter { it.code.endsWith("00") }
        if (real.isNotEmpty()) return real
        if (!loaded) return direct
        // 直辖市：把区县当下一级
        val fromBounds = districtBounds.filter { it.parent == provinceCode }.map { it.toUnit() }
        return if (fromBounds.isNotEmpty()) fromBounds else direct
    }

    /**
     * 某个市下的区县。
     *
     * 优先用点表（districts.json），但它可能是空的 —— 快速构建版没拉区县中心点表
     * （要 500 多次 HTTP）。空的时候从**区县边界表**里取，边界表里每个单元都带了
     * name/center/parent，能直接当点表用。
     * 这就是"选区县没有多级菜单"的根因：之前只查点表，空表直接返回空列表。
     */
    fun districtsOf(cityCode: String): List<Unit> {
        districtsByCity[cityCode]?.let { if (it.isNotEmpty()) return it }
        if (!loaded) return emptyList()
        return districtBounds.filter { it.parent == cityCode }.map { it.toUnit() }
    }

    fun findProvince(code: String): Unit? = provinces.firstOrNull { it.code == code }

    fun findUnit(code: String): Unit? {
        provinces.firstOrNull { it.code == code }?.let { return it }
        citiesByProvince.values.forEach { list ->
            list.firstOrNull { it.code == code }?.let { return it }
        }
        districtsByCity.values.forEach { list ->
            list.firstOrNull { it.code == code }?.let { return it }
        }
        return null
    }

    /** 一个点归到哪个行政区。返回 null 表示判定为境外（离所有省中心都太远）。 */
    data class Assignment(
        val provinceCode: String,
        val provinceName: String,
        val cityCode: String,
        val cityName: String,
        val districtCode: String,
        val districtName: String,
    )

    /**
     * 境外判定阈值（公里）。
     *
     * 为什么用"离最近省中心超过 500 km"：中国最西端到最东端约 5200 km，
     * 但省中心密度很高，正常境内的点离某个省中心不会超过 500 km。
     * 实测例：宜春到南昌约 130 km、喀什到乌鲁木齐约 1000 km —— 所以喀什这种
     * 新疆边角会有误判风险，宁可放宽到 800 km。
     */
    private const val ABROAD_KM = 800.0

    /**
     * 给一个 WGS-84 坐标算行政区归属。
     *
     * 顺序：最近的省 → 该省里最近的地市 → 该地市里最近的区县。
     * 直辖市没有地市这一级（构建期已把它的区县直接挂到省下），此时 city 留空、
     * district 从省级的 districts 里找。
     */
    fun assign(lon: Double, lat: Double): Assignment? {
        if (!loaded || provinces.isEmpty()) return null
        // 先用边界精确定省；边界判不出来（数据缺口/境外）再退回最近中心
        val prov = provinceByPolygon(lon, lat)
            ?: nearest(provinces, lon, lat)?.also {
                // 最近中心也离得很远，就当境外
                if (distanceKm(lon, lat, it.lon, it.lat) > ABROAD_KM) return null
            }
            ?: return null

        // 直辖市的区县直接挂在省 code 下
        val cities = citiesOf(prov.code)
        if (cities.isEmpty()) {
            val districts = districtsOf(prov.code)
            val d = nearest(districts, lon, lat)
            return Assignment(
                provinceCode = prov.code,
                provinceName = prov.name,
                cityCode = "",
                cityName = "",
                districtCode = d?.code ?: "",
                districtName = d?.name ?: "",
            )
        }

        // 定市：先试边界（准），再退回最近中心
        val city = cityByPolygon(lon, lat, prov.code) ?: nearest(cities, lon, lat)
            ?: return Assignment(
            provinceCode = prov.code, provinceName = prov.name,
            cityCode = "", cityName = "", districtCode = "", districtName = "",
        )
        val districts = districtsOf(city.code)
        // 区县：先试边界（准），再退回最近中心
        val district = districtByPolygon(lon, lat, city.code) ?: nearest(districts, lon, lat)
        return Assignment(
            provinceCode = prov.code,
            provinceName = prov.name,
            cityCode = city.code,
            cityName = city.name,
            districtCode = district?.code ?: "",
            districtName = district?.name ?: "",
        )
    }

    /**
     * 用省级边界做 point-in-polygon。
     *
     * 射线法（ray casting）：从点向右发一条射线，数它与多边形边的交点，
     * 奇数次在内部、偶数次在外部。对所有省的**所有块**都试一遍，
     * 命中就返回（正常情况下第一个省就命中，境外会遍历完 34 个后返回 null）。
     */
    fun provinceByPolygon(lon: Double, lat: Double): Unit? {
        if (!loaded) return null
        val x = lon.toFloat()
        val y = lat.toFloat()
        for (b in provinceBounds) {
            for (ring in b.rings) {
                if (pointInRing(ring, x, y)) {
                    // 优先用点表里的（信息全），没有就用边界自带的信息
                    return provinces.firstOrNull { it.code == b.code } ?: b.toUnit()
                }
            }
        }
        return null
    }

    /** 射线法判点是否在环内。ring 是 [lon0,lat0,lon1,lat1,...] 扁平数组。 */
    private fun pointInRing(ring: FloatArray, x: Float, y: Float): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[i * 2]
            val yi = ring[i * 2 + 1]
            val xj = ring[j * 2]
            val yj = ring[j * 2 + 1]
            // 判断边 (xj,yj)-(xi,yi) 是否与向右射线相交
            if ((yi > y) != (yj > y)) {
                val xCross = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 找出坐标落在哪个省（用于分层视图判断"当前在哪个省"） */
    fun provinceAt(lon: Double, lat: Double): Unit? {
        if (!loaded) return null
        // 优先用边界（准），边界缺失时退回最近中心
        return provinceByPolygon(lon, lat) ?: nearest(provinces, lon, lat)
    }

    /**
     * 用市级边界精确定市，限定在某个省内找（避免跨省误命中飞地）。
     *
     * 为什么不全国一把梭：省界附近的点可能同时落在两个省的多边形里（边界重叠、
     * 数据精度导致的缝隙），先定省再在省内定市能把这层歧义消掉。
     */
    fun cityByPolygon(lon: Double, lat: Double, provinceCode: String): Unit? {
        if (!loaded) return null
        val x = lon.toFloat()
        val y = lat.toFloat()
        for (b in cityBounds) {
            for (ring in b.rings) {
                if (pointInRing(ring, x, y)) {
                    val u = findUnit(b.code) ?: b.toUnit()
                    // 只认本省的市，防止边界重叠导致的跨省误判
                    if (provinceCode.isEmpty() || u.parent == provinceCode) return u
                }
            }
        }
        return null
    }

    /** 省级边界（画省界用） */
    fun boundsOfProvinces(): List<Bound> = provinceBounds

    /**
     * 市级边界。
     *
     * 注意要过滤掉直辖市的区县：北京/上海的省级文件里直接是区县，
     * 构建时混进了 city_bounds（第一个就是"东城区"）。
     * 判据：adcode 第 5、6 位不是 00 的，说明它是区县级而不是地市级。
     */
    fun boundsOfCities(): List<Bound> =
        cityBounds.filter { it.code.length == 6 && it.code.endsWith("00") }

    /** 区县边界 */
    fun boundsOfDistricts(): List<Bound> = districtBounds

    /**
     * 从边界数据里找一个单元（省/市/区县都查）。
     *
     * 为什么需要它：区县的点表 districts.json 可能是空的（快速构建版没拉），
     * 而边界表里每个单元都带了名字和中心点，能当点表用。
     */
    fun unitFromBounds(code: String): Unit? {
        if (!loaded) return null
        provinceBounds.firstOrNull { it.code == code }?.let { return it.toUnit() }
        cityBounds.firstOrNull { it.code == code }?.let { return it.toUnit() }
        districtBounds.firstOrNull { it.code == code }?.let { return it.toUnit() }
        return null
    }

    /**
     * 取某个行政单元的外接框 [minLon, minLat, maxLon, maxLat]。
     *
     * 用途：下钻到某一层时把相机框住整个单元。实测教训——只飞市中心的话，
     * 辖区大的市（如宜春，东西跨 2 度）下属区县气泡会全落在屏幕外，
     * 用户看到一片空白还以为坏了。
     */
    fun bboxOf(code: String): DoubleArray? {
        if (!loaded) return null
        val b = provinceBounds.firstOrNull { it.code == code }
            ?: cityBounds.firstOrNull { it.code == code }
            ?: districtBounds.firstOrNull { it.code == code }
            ?: return null
        var minLon = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        for (ring in b.rings) {
            var i = 0
            while (i + 1 < ring.size) {
                val lon = ring[i].toDouble()
                val lat = ring[i + 1].toDouble()
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                i += 2
            }
        }
        if (minLon > maxLon) return null
        return doubleArrayOf(minLon, minLat, maxLon, maxLat)
    }

    /** 用区县边界精确判定（限定在某个市内） */
    fun districtByPolygon(lon: Double, lat: Double, cityCode: String): Unit? {
        if (!loaded || districtBounds.isEmpty()) return null
        val x = lon.toFloat()
        val y = lat.toFloat()
        for (b in districtBounds) {
            for (ring in b.rings) {
                if (pointInRing(ring, x, y)) {
                    val u = districtUnit(b.code) ?: b.toUnit()
                    if (cityCode.isEmpty() || u.parent == cityCode) return u
                }
            }
        }
        return null
    }

    /** 在区县里找一个单元（findUnit 会扫三张表，这里只扫区县那张，快一些） */
    private fun districtUnit(code: String): Unit? {
        // districtsByCity 是 parent -> list 的映射，遍历值集合找
        for (list in districtsByCity.values) {
            list.firstOrNull { it.code == code }?.let { return it }
        }
        return null
    }

    /** 找出坐标落在哪个地市 */
    fun cityAt(lon: Double, lat: Double): Unit? {
        val prov = provinceAt(lon, lat) ?: return null
        // 优先边界，退回最近中心
        cityByPolygon(lon, lat, prov.code)?.let { return it }
        val cities = citiesOf(prov.code)
        if (cities.isEmpty()) return null
        return nearest(cities, lon, lat)
    }

    private fun nearest(list: List<Unit>, lon: Double, lat: Double): Unit? {
        if (list.isEmpty()) return null
        var best: Unit? = null
        var bestD = Double.MAX_VALUE
        for (u in list) {
            val d = distanceKm(lon, lat, u.lon, u.lat)
            if (d < bestD) {
                bestD = d
                best = u
            }
        }
        return best
    }

    /** Haversine，公里。和 ZoomGuard 里那份算法一致，但那边是 public 的静态工具，这里不重复实现。 */
    fun distanceKm(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * Math.atan2(sqrt(a), sqrt(1 - a))
    }

    // ---------------- 境外网格 ----------------

    /**
     * 境外的分层：按经纬度网格聚合。
     *
     * 为什么用网格而不是国家：DataV 只有中国数据，`world.json` 实测 404。
     * 网格化不需要第二个数据源、不需要处理英文地名，全世界都能覆盖。
     *
     * 网格大小随层级变化，保证每层屏幕上的气泡数量差不多：
     * L0 用 10°、L1 用 2°、L2 用 0.5°。
     */
    fun gridSizeFor(level: Int): Double = when {
        level <= 0 -> 10.0
        level == 1 -> 2.0
        else -> 0.5
    }

    /** 一个坐标属于哪个网格（返回稳定 key，如 "g0.5_22.5_114.0"） */
    fun gridKey(lon: Double, lat: Double, size: Double): String {
        val gx = Math.floor(lon / size) * size
        val gy = Math.floor(lat / size) * size
        return "g${size}_${fmt(gy)}_${fmt(gx)}"
    }

    /** 网格中心点 */
    fun gridCenter(key: String, size: Double): Pair<Double, Double> {
        // key 形如 g10.0_20.0_110.0 -> lat=20, lon=110
        val body = key.substringAfter('_')
        val parts = body.split('_')
        if (parts.size < 2) return 0.0 to 0.0
        val gy = parts[0].toDoubleOrNull() ?: 0.0
        val gx = parts[1].toDoubleOrNull() ?: 0.0
        return (gx + size / 2) to (gy + size / 2)
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (abs(r - Math.round(r).toDouble()) < 1e-9) {
            Math.round(r).toString()
        } else {
            r.toString()
        }
    }
}
