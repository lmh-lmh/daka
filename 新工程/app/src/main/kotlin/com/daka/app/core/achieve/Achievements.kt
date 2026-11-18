package com.daka.app.core.achieve

import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.SpotDao
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 成就系统。
 *
 * 设计原则（对应你"不要太过繁琐"的要求）：
 * 1. **不新建表**。所有成就都是"从打卡点现算出来的"，省掉一张表、省掉迁移、
 *    也省掉"数据不一致"这一整类 bug。
 * 2. **不弹窗打断**。解锁只在成就页里看到，不在拍照后弹"恭喜解锁"，那样太吵。
 * 3. **和分层地图配套**：省级气泡上可以直接显示这个省拿了几个成就，
 *    一眼看出"哪个省我玩得最透"。
 *
 * 成就分四类：
 * - 数量类：打卡 1/10/50/100/500 个点
 * - 地域类：去过 1/3/5/10 个省、1/5/20 个市
 * - 深度类：单个省打卡 10/50 个点（"泡透一个地方"）
 * - 时间类：连续 3/7/30 天有打卡；单日打卡 5 个点
 */
object Achievements {

    enum class Kind(val label: String) {
        COUNT("数量"),
        REGION("足迹"),
        DEPTH("深度"),
        TIME("坚持"),
    }

    /**
     * 一条成就的静态定义。
     *
     * @param target 达成阈值；`progressOf` 返回当前进度，>= target 即解锁
     */
    data class Def(
        val id: String,
        val title: String,
        val desc: String,
        val kind: Kind,
        val target: Int,
        val icon: String,
    )

    /** 当前进度 */
    data class State(
        val def: Def,
        val progress: Int,
    ) {
        val unlocked: Boolean get() = progress >= def.target
        /** 0..1，用于画进度条 */
        val ratio: Float get() = if (def.target <= 0) 1f else (progress.toFloat() / def.target).coerceIn(0f, 1f)
    }

    /** 统计快照：一次性从库里算出来，下面所有成就都基于它，避免重复查库 */
    data class Stats(
        val spotCount: Int,
        val photoCount: Int,
        val provinceCount: Int,
        val cityCount: Int,
        val maxProvinceSpotCount: Int,
        val distinctDays: Int,
        val longestStreak: Int,
        val maxSpotsInOneDay: Int,
    )

    val ALL: List<Def> = listOf(
        // 数量
        Def("first", "起点", "打卡第 1 个地方", Kind.COUNT, 1, "📍"),
        Def("c10", "上路了", "打卡 10 个地方", Kind.COUNT, 10, "🚶"),
        Def("c50", "常客", "打卡 50 个地方", Kind.COUNT, 50, "🧭"),
        Def("c100", "足迹百点", "打卡 100 个地方", Kind.COUNT, 100, "🏅"),
        Def("c500", "五百里挑一", "打卡 500 个地方", Kind.COUNT, 500, "💎"),
        Def("p100", "光影一百", "累计拍 100 张照片", Kind.COUNT, 100, "📷"),
        Def("p1000", "光影一千", "累计拍 1000 张照片", Kind.COUNT, 1000, "🎞"),

        // 地域
        Def("pr1", "出省了", "打卡过 1 个省", Kind.REGION, 1, "🗺"),
        Def("pr3", "三省吾身", "打卡过 3 个省", Kind.REGION, 3, "🧳"),
        Def("pr5", "五湖四海", "打卡过 5 个省", Kind.REGION, 5, "🌏"),
        Def("pr10", "十省通衢", "打卡过 10 个省", Kind.REGION, 10, "🏆"),
        Def("ct5", "走街串巷", "打卡过 5 个城市", Kind.REGION, 5, "🏙"),
        Def("ct20", "二十城记", "打卡过 20 个城市", Kind.REGION, 20, "🌆"),

        // 深度
        Def("d10", "泡透一地", "在同一个省打卡 10 个地方", Kind.DEPTH, 10, "🫖"),
        Def("d50", "深度玩家", "在同一个省打卡 50 个地方", Kind.DEPTH, 50, "🔍"),

        // 时间
        Def("s3", "三日不断", "连续 3 天打卡", Kind.TIME, 3, "🔥"),
        Def("s7", "一周坚持", "连续 7 天打卡", Kind.TIME, 7, "⭐"),
        Def("s30", "月度恒心", "连续 30 天打卡", Kind.TIME, 30, "👑"),
        Def("day5", "一日五景", "同一天打卡 5 个地方", Kind.TIME, 5, "🌅"),
        Def("days30", "三十天足迹", "累计 30 天有打卡记录", Kind.TIME, 30, "📅"),
    )

    /**
     * 算统计快照。
     *
     * 为什么这些都从 spot 表现算而不是存计数器：计数器一旦漏加就永远对不上，
     * 而几千条记录扫一遍是毫秒级，每次都现算最不容易错。
     */
    fun stats(dao: SpotDao): Stats {
        val spots = dao.all(limit = 20000)
        val photoTotal = spots.sumOf { it.photoCount }

        val byProvince = spots.filter { it.provinceCode.isNotEmpty() }
            .groupingBy { it.provinceCode }.eachCount()
        val byCity = spots.filter { it.cityCode.isNotEmpty() }
            .groupingBy { it.cityCode }.eachCount()

        // 按天聚合（本地时区）
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val byDay = HashMap<String, Int>()
        for (s in spots) {
            val d = fmt.format(Date(s.createdAt))
            byDay[d] = (byDay[d] ?: 0) + 1
        }

        return Stats(
            spotCount = spots.size,
            photoCount = photoTotal,
            provinceCount = byProvince.size,
            cityCount = byCity.size,
            maxProvinceSpotCount = byProvince.values.maxOrNull() ?: 0,
            distinctDays = byDay.size,
            longestStreak = longestStreak(byDay.keys.mapNotNull { parseDay(it) }),
            maxSpotsInOneDay = byDay.values.maxOrNull() ?: 0,
        )
    }

    /** 把所有成就算成带进度的状态 */
    fun evaluate(stats: Stats): List<State> = ALL.map { def ->
        val p = when (def.id) {
            "first", "c10", "c50", "c100", "c500" -> stats.spotCount
            "p100", "p1000" -> stats.photoCount
            "pr1", "pr3", "pr5", "pr10" -> stats.provinceCount
            "ct5", "ct20" -> stats.cityCount
            "d10", "d50" -> stats.maxProvinceSpotCount
            "s3", "s7", "s30" -> stats.longestStreak
            "day5" -> stats.maxSpotsInOneDay
            "days30" -> stats.distinctDays
            else -> 0
        }
        State(def, p)
    }

    fun unlockedCount(states: List<State>): Int = states.count { it.unlocked }

    /**
     * 某个省拿到了哪些成就。
     *
     * 用途：省级气泡上显示"这个省拿了几个成就"，点开能看到具体哪几个。
     * 这里只算和这个省有关的：该省的点数、该省的城市数、该省是否有连续打卡。
     */
    fun forProvince(dao: SpotDao, provinceCode: String): List<State> {
        val spots = dao.spotsOfProvince(provinceCode, limit = 20000)
        if (spots.isEmpty()) return emptyList()
        val byCity = spots.filter { it.cityCode.isNotEmpty() }.groupingBy { it.cityCode }.eachCount()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val byDay = HashMap<String, Int>()
        for (s in spots) {
            val d = fmt.format(Date(s.createdAt))
            byDay[d] = (byDay[d] ?: 0) + 1
        }
        val st = Stats(
            spotCount = spots.size,
            photoCount = spots.sumOf { it.photoCount },
            provinceCount = 1,
            cityCount = byCity.size,
            maxProvinceSpotCount = spots.size,
            distinctDays = byDay.size,
            longestStreak = longestStreak(byDay.keys.mapNotNull { parseDay(it) }),
            maxSpotsInOneDay = byDay.values.maxOrNull() ?: 0,
        )
        return evaluate(st)
    }

    // ---------------- 连续天数 ----------------

    private fun parseDay(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    /**
     * 最长的连续打卡天数。
     *
     * 算法：按天去重排序，相邻两天差正好 86400000 毫秒算连续。
     * 注意用 Calendar 归零到当天 0 点再比较，避免夏令时/时区导致的 23/25 小时误差。
     */
    fun longestStreak(days: List<Long>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.map { startOfDay(it) }.distinct().sorted()
        var best = 1
        var cur = 1
        for (i in 1 until sorted.size) {
            val diff = sorted[i] - sorted[i - 1]
            if (diff == 86_400_000L) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 1
            }
        }
        return best
    }

    private fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
