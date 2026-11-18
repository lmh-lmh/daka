package com.daka.app.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 数据库。
 *
 * 为什么手写 SQLiteOpenHelper 而不用 Room：Room 需要 KSP，KSP 版本与 Kotlin 版本强耦合，
 * 本机 .gradle-home 缓存里 ksp/kapt/room/sqlite 全都是空的，全靠联网下载；而且引入 Room
 * 会带来一整套注解处理器。这里表结构简单（7 张表），手写 SQL 成本约 300 行，一次性投入，
 * 换来零新增依赖。详见 02-技术方案.md「手写 SQLite 的代价与边界」。
 */
class DakaDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "daka.db"
        const val DB_VERSION = 2
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 必须在这里开，不能在 onOpen/onCreate 里开（官方要求事务外调用）
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spot (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                lon         REAL    NOT NULL,
                lat         REAL    NOT NULL,
                name        TEXT    NOT NULL,
                note        TEXT    NOT NULL DEFAULT '',
                category    TEXT    NOT NULL DEFAULT '未分类',
                rating      INTEGER NOT NULL DEFAULT 0,
                favorite    INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL,
                shape       TEXT    NOT NULL DEFAULT 'pin',
                color       INTEGER NOT NULL,
                size        REAL    NOT NULL DEFAULT 1.0,
                icon_file   TEXT    NOT NULL DEFAULT '',
                text        TEXT    NOT NULL DEFAULT '',
                -- v2：行政区归属。境外点这 6 列全空，分层视图走网格聚合
                province_code TEXT NOT NULL DEFAULT '',
                province_name TEXT NOT NULL DEFAULT '',
                city_code     TEXT NOT NULL DEFAULT '',
                city_name     TEXT NOT NULL DEFAULT '',
                district_code TEXT NOT NULL DEFAULT '',
                district_name TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        // 视口查询靠这两个；时间轴靠 created_at
        db.execSQL("CREATE INDEX idx_spot_lonlat ON spot(lon, lat)")
        db.execSQL("CREATE INDEX idx_spot_created ON spot(created_at DESC)")
        // 分层视图按行政区 GROUP BY，这两个索引是它的性能基础
        db.execSQL("CREATE INDEX idx_spot_province ON spot(province_code)")
        db.execSQL("CREATE INDEX idx_spot_city ON spot(city_code)")

        db.execSQL(
            """
            CREATE TABLE photo (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                spot_id     INTEGER NOT NULL REFERENCES spot(id) ON DELETE CASCADE,
                uri         TEXT    NOT NULL,
                rel_path    TEXT    NOT NULL,
                thumb_file  TEXT    NOT NULL DEFAULT '',
                bytes       INTEGER NOT NULL DEFAULT 0,
                width       INTEGER NOT NULL DEFAULT 0,
                height      INTEGER NOT NULL DEFAULT 0,
                lon         REAL,
                lat         REAL,
                taken_at    INTEGER NOT NULL,
                sha256      TEXT    NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_photo_spot ON photo(spot_id, taken_at DESC)")
        // 去重：同一个文件被重复选中时不重复入库
        db.execSQL("CREATE UNIQUE INDEX idx_photo_sha ON photo(sha256) WHERE sha256 <> ''")

        db.execSQL(
            """
            CREATE TABLE tag (
                id    INTEGER PRIMARY KEY AUTOINCREMENT,
                name  TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE spot_tag (
                spot_id INTEGER NOT NULL REFERENCES spot(id) ON DELETE CASCADE,
                tag_id  INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
                PRIMARY KEY (spot_id, tag_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE category (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                name      TEXT    NOT NULL UNIQUE,
                color     INTEGER NOT NULL,
                icon      TEXT    NOT NULL DEFAULT '',
                sort      INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // 预置分类（用户要"分类 + 标签 + 评分 + 收藏"）
        db.execSQL(
            """
            INSERT INTO category(name, color, icon, sort) VALUES
            ('美食', ${0xFFE53935.toInt()}, '🍜', 1),
            ('风景', ${0xFF43A047.toInt()}, '🏞', 2),
            ('旅行', ${0xFF1E88E5.toInt()}, '🧭', 3),
            ('日常', ${0xFF757575.toInt()}, '📌', 4),
            ('未分类', ${0xFF9E9E9E.toInt()}, '', 9)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE track_pt (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                lon         REAL    NOT NULL,
                lat         REAL    NOT NULL,
                accuracy    REAL    NOT NULL DEFAULT 0,
                recorded_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_track_time ON track_pt(recorded_at)")

        db.execSQL(
            """
            CREATE TABLE meta (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2：给 spot 加行政区归属。
            // 为什么全部 NOT NULL DEFAULT ''：存量点先落空串，启动后由
            // RegionBackfill 在后台按经纬度批量补算，不阻塞用户看到地图。
            db.execSQL("ALTER TABLE spot ADD COLUMN province_code TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN province_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN city_code     TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN city_name     TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN district_code TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spot ADD COLUMN district_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_spot_province ON spot(province_code)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_spot_city ON spot(city_code)")
        }
    }
}
