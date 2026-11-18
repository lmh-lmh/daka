package com.daka.app.core.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.daka.app.core.db.DakaDb
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复。
 *
 * 打包内容：daka.db（打卡点、照片记录、分类、轨迹）+ thumbs/（缩略图）+ icons/（自定义图标）。
 * **不打包原图**——原图在系统相册里，用户自己用相册同步/网盘备份，App 再存一份纯属浪费空间。
 *
 * 恢复的策略是"先验后覆盖"：先读 zip 里的 daka.db 头 16 字节确认真的是 SQLite，
 * 再备份当前库为 daka.db.bak，最后覆盖。做错了能人工捞回来。
 */
object Backup {

    private const val DB_NAME = "daka.db"
    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    data class Result(val ok: Boolean, val message: String, val bytes: Long = 0)

    /** 打包到系统「下载」目录，Android 10+ 走 MediaStore（不需要存储权限）。 */
    fun export(ctx: Context): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "打卡备份_$stamp.zip"

        val out: File
        val target: Uri?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/打卡备份")
            }
            target = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return Result(false, "建不了备份文件，系统不让写下载目录")
            out = File(ctx.cacheDir, fileName)
        } else {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "打卡备份"
            )
            dir.mkdirs()
            out = File(dir, fileName)
            target = null
        }

        var total = 0L
        try {
            FileOutputStream(out).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    total += zipFile(zos, File(ctx.filesDir, DB_NAME), DB_NAME)
                    // SQLite 的 -wal / -shm 不打包：备份前先 checkpoint 到主库
                    total += zipDir(zos, File(ctx.filesDir, "thumbs"), "thumbs/")
                    total += zipDir(zos, File(ctx.filesDir, "icons"), "icons/")
                }
            }
            if (target != null) {
                ctx.contentResolver.openOutputStream(target)?.use { os ->
                    FileInputStream(out).use { it.copyTo(os) }
                } ?: return Result(false, "写不进下载目录")
            }
            return Result(true, "已备份到「下载/打卡备份/$fileName」（${total / 1024} KB）", total)
        } catch (e: Exception) {
            return Result(false, "备份失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            out.delete()
        }
    }

    /**
     * 从用户选中的 zip 恢复。
     *
     * 注意：会直接覆盖当前数据库。调用方必须先确认（弹窗问过用户），
     * 并且恢复后**必须重启 App**（当前进程里所有 DAO 还连着旧库句柄）。
     */
    fun import(ctx: Context, zipUri: Uri): Result {
        val tmpDir = File(ctx.cacheDir, "restore_tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        try {
            var dbBytes = 0L
            ctx.contentResolver.openInputStream(zipUri)?.use { ins ->
                ZipInputStream(ins).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val name = e.name ?: ""
                        // 防路径穿越：拒绝绝对路径和 ..
                        if (name.contains("..") || name.startsWith("/")) {
                            zis.closeEntry(); e = zis.nextEntry; continue
                        }
                        when {
                            name == DB_NAME -> {
                                val f = File(tmpDir, DB_NAME)
                                FileOutputStream(f).use { zis.copyTo(it) }
                                dbBytes = f.length()
                            }
                            name.startsWith("thumbs/") || name.startsWith("icons/") -> {
                                val f = File(tmpDir, name)
                                f.parentFile?.mkdirs()
                                FileOutputStream(f).use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            } ?: return Result(false, "打不开这个文件")

            val newDb = File(tmpDir, DB_NAME)
            if (!newDb.exists() || dbBytes < 100) {
                return Result(false, "这个压缩包里没有打卡数据库，选错文件了？")
            }
            if (!looksLikeSqlite(newDb)) {
                return Result(false, "包里的数据库文件坏了，不敢覆盖现在的数据")
            }

            // 覆盖前先留一份现状，万一是误操作还能人工捞回来
            val cur = File(ctx.filesDir, DB_NAME)
            if (cur.exists()) {
                cur.copyTo(File(ctx.filesDir, "$DB_NAME.bak"), overwrite = true)
            }
            // 干掉 -wal/-shm，否则旧 WAL 会盖掉刚恢复的主库
            File(ctx.filesDir, "$DB_NAME-wal").delete()
            File(ctx.filesDir, "$DB_NAME-shm").delete()

            newDb.copyTo(cur, overwrite = true)

            copyDir(File(tmpDir, "thumbs"), File(ctx.filesDir, "thumbs"))
            copyDir(File(tmpDir, "icons"), File(ctx.filesDir, "icons"))

            return Result(true, "恢复完成。请把 App 完全关掉再打开（返回键退出后从桌面重进）", dbBytes)
        } catch (e: Exception) {
            return Result(false, "恢复失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 备份/恢复前把 WAL 落盘，否则最近写的数据还在 -wal 里没进主库。 */
    fun checkpoint(db: DakaDb) {
        try {
            val c = db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null)
            c.moveToFirst()
            c.close()
        } catch (e: Exception) {
        }
    }

    private fun looksLikeSqlite(f: File): Boolean {
        return try {
            val buf = ByteArray(16)
            FileInputStream(f).use { it.read(buf) }
            String(buf, Charsets.ISO_8859_1) == SQLITE_MAGIC
        } catch (e: Exception) {
            false
        }
    }

    private fun zipFile(zos: ZipOutputStream, f: File, entry: String): Long {
        if (!f.exists()) return 0
        zos.putNextEntry(ZipEntry(entry))
        FileInputStream(f).use { it.copyTo(zos) }
        zos.closeEntry()
        return f.length()
    }

    private fun zipDir(zos: ZipOutputStream, dir: File, prefix: String): Long {
        if (!dir.isDirectory) return 0
        var n = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile) n += zipFile(zos, f, prefix + f.name)
        }
        return n
    }

    private fun copyDir(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        from.listFiles()?.forEach { f ->
            if (f.isFile) {
                f.copyTo(File(to, f.name), overwrite = true)
            }
        }
    }
}
