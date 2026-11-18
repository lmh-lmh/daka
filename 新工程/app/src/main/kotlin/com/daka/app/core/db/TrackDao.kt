package com.daka.app.core.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 足迹轨迹点的读写。
 *
 * 数据量估算：一次出门走 2 小时、5 秒一个点 = 1440 个点，一天最多几千。
 * 所以不做"一边写一边算距离"这种花活，全部落库，展示时一次性读当天。
 */
class TrackDao(private val helper: DakaDb) {

    private fun db(): SQLiteDatabase = helper.writableDatabase

    data class Point(
        val lon: Double,
        val lat: Double,
        val accuracy: Float,
        val recordedAt: Long,
    )

    /** 某天的点（按时间正序）。day 形如 "2026-11-13"，本地时区。 */
    fun ofDay(day: String): List<Point> {
        val (start, end) = dayRange(day) ?: return emptyList()
        val out = ArrayList<Point>()
        db().rawQuery(
            "SELECT lon, lat, accuracy, recorded_at FROM track_pt " +
                "WHERE recorded_at >= ? AND recorded_at < ? ORDER BY recorded_at ASC",
            arrayOf(start.toString(), end.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Point(c.getDouble(0), c.getDouble(1), c.getFloat(2), c.getLong(3)))
            }
        }
        return out
    }

    /** 最近 n 个有轨迹的日期，倒序（最新的在前）。 */
    fun recentDays(limit: Int = 30): List<String> {
        val out = ArrayList<String>()
        db().rawQuery(
            "SELECT DISTINCT date(recorded_at / 1000, 'unixepoch', 'localtime') AS d " +
                "FROM track_pt ORDER BY d DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun insert(p: Point): Long = db().insert("track_pt", null, ContentValues().apply {
        put("lon", p.lon)
        put("lat", p.lat)
        put("accuracy", p.accuracy)
        put("recorded_at", p.recordedAt)
    })

    /** 批量插入（一次采集间断攒的点），省掉一万一的回调开销。 */
    fun insertAll(points: List<Point>) {
        if (points.isEmpty()) return
        val d = db()
        d.beginTransaction()
        try {
            for (p in points) {
                d.insert("track_pt", null, ContentValues().apply {
                    put("lon", p.lon)
                    put("lat", p.lat)
                    put("accuracy", p.accuracy)
                    put("recorded_at", p.recordedAt)
                })
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun deleteDay(day: String): Int {
        val (start, end) = dayRange(day) ?: return 0
        return db().delete(
            "track_pt",
            "recorded_at >= ? AND recorded_at < ?",
            arrayOf(start.toString(), end.toString()),
        )
    }

    fun countAll(): Int {
        db().rawQuery("SELECT COUNT(*) FROM track_pt", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun lastPoint(): Point? {
        db().rawQuery(
            "SELECT lon, lat, accuracy, recorded_at FROM track_pt ORDER BY recorded_at DESC LIMIT 1",
            null,
        ).use { c ->
            return if (c.moveToNext()) {
                Point(c.getDouble(0), c.getDouble(1), c.getFloat(2), c.getLong(3))
            } else null
        }
    }

    companion object {
        private val FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** "2026-11-13" -> 当天的 [起, 止) 毫秒。格式不对返回 null。 */
        fun dayRange(day: String): Pair<Long, Long>? {
            return try {
                val d = FMT.parse(day) ?: return null
                val start = d.time
                start to (start + 24L * 3600 * 1000)
            } catch (e: Exception) {
                null
            }
        }

        fun today(): String = FMT.format(Date())

        /** 把一个时间戳格式化成日期字符串，和 [today] 同一套格式。 */
        fun dayOf(millis: Long): String = FMT.format(Date(millis))

        /** "2026-11-13" -> "11月13日 周四"，给轨迹列表用。 */
        fun prettyDay(day: String): String {
            return try {
                val d = FMT.parse(day) ?: return day
                SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(d)
            } catch (e: Exception) {
                day
            }
        }
    }
}
