package com.daka.app.core.track

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.daka.app.core.db.TrackDao
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把某一天的轨迹导出成 GPX。
 *
 * 为什么是 GPX：这是户外/运动圈唯一通用的轨迹交换格式，Strava、两步路、奥维、
 * Google Earth 都能直接导入。导出的文件存到公共 Download 目录，用户自己拿去用。
 *
 * 坐标一律用 WGS-84（就是 GPS 原始的经纬度），不加任何偏移——
 * 国内某些地图有 GCJ-02 偏移，但我们记的就是 GPS 原始值，直接写才对得上。
 */
object GpxExporter {

    data class Result(val ok: Boolean, val message: String, val path: String = "")

    /** 一天一条轨迹。points 必须是当天按时间正序的点。 */
    fun buildXml(day: String, points: List<TrackDao.Point>): String {
        val sb = StringBuilder(1024 * 8)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="打卡" """)
        sb.append("""xmlns="http://www.topografix.com/GPX/1/1" """)
        sb.append("""xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
        sb.append(
            """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(esc("打卡 $day")).append("</name>\n")
        sb.append("    <time>").append(iso(points.firstOrNull()?.recordedAt ?: 0L)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(esc(day)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            // 注意：这里不能用 """ 原样字符串，结尾的引号会被当成分隔符吃掉
            sb.append("      <trkpt lat=\"").append(fmt(p.lat))
            sb.append("\" lon=\"").append(fmt(p.lon)).append("\">\n")
            if (p.recordedAt > 0) {
                sb.append("        <time>").append(iso(p.recordedAt)).append("</time>\n")
            }
            if (p.accuracy > 0f) {
                // GPX 没有精度字段，标准做法是用 hdop 近似表达（数值越小越准）
                sb.append("        <hdop>").append(fmt((p.accuracy / 5f).toDouble())).append("</hdop>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * 导出到公共 Download/打卡轨迹/ 目录。
     * Android 10+ 走 MediaStore（不需要存储权限），Android 9 直接写文件。
     */
    fun export(ctx: Context, day: String, points: List<TrackDao.Point>): Result {
        if (points.isEmpty()) return Result(false, "这一天没有轨迹，没东西可导")
        val xml = buildXml(day, points)
        val name = "打卡轨迹_$day.gpx"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/打卡轨迹")
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                ) ?: return Result(false, "系统没给出保存位置，可能存储空间不足")
                ctx.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    out.write(xml.toByteArray(Charsets.UTF_8))
                } ?: return Result(false, "打不开刚创建的文件")
                Result(true, "已导出 $name", "Download/打卡轨迹/$name")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "打卡轨迹"
                )
                if (!dir.exists() && !dir.mkdirs()) return Result(false, "建不了目录 ${dir.absolutePath}")
                val f = File(dir, name)
                f.writeText(xml, Charsets.UTF_8)
                Result(true, "已导出 $name", f.absolutePath)
            }
        } catch (e: Exception) {
            Result(false, "导出失败：${e.message}")
        }
    }

    private fun fmt(d: Double) = String.format(Locale.US, "%.7f", d)

    private fun iso(millis: Long): String {
        if (millis <= 0) return ""
        // GPX 要 UTC 的 ISO-8601，不是本地时间
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
