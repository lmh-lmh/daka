package com.daka.app.core.track

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.daka.app.MainActivity
import com.daka.app.R
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 足迹记录。前台服务 + 系统 LocationManager，不引 GMS。
 *
 * 为什么必须是前台服务：Android 8 之后后台服务跑不满 1 分钟就会被系统掐掉，
 * 而用户出门走路时 App 常常在后台。前台服务需要常驻通知，这是系统硬要求，
 * 关不掉；通知文案必须是能看懂的人话，不能骗用户。
 *
 * 采样策略：5 秒 / 10 米，比导航类 App 稀疏得多——足迹只要大致轮廓，
 * 采样太密只会让电池掉得快、数据库涨得快。
 * 攒够 20 个点或者过了 60 秒才批量入库，避免每个点都写一次盘。
 */
class TrackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: DakaDb
    private lateinit var dao: TrackDao

    private val buffer = ArrayList<TrackDao.Point>()
    private var lastFlush = 0L

    private val flusher = Handler(Looper.getMainLooper())
    private val flushTask = object : Runnable {
        override fun run() {
            flush()
            flusher.postDelayed(this, 60_000L)
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 精度太差的点（>80m）直接丢，否则轨迹会画出一团乱麻
            if (location.accuracy > 80f) return
            synchronized(buffer) {
                buffer.add(
                    TrackDao.Point(
                        lon = location.longitude,
                        lat = location.latitude,
                        accuracy = location.accuracy,
                        recordedAt = System.currentTimeMillis(),
                    )
                )
                if (buffer.size >= 20) flushLocked()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        db = DakaDb(this)
        dao = TrackDao(db)
        Recorder.running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                flush()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startInForeground()
        startUpdates()
        flusher.removeCallbacks(flushTask)
        flusher.postDelayed(flushTask, 60_000L)
        return START_STICKY
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("正在记录足迹")
            .setContentText("走完记得在这里点「停止」，关掉不省电")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止记录", stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                stopSelf()
                return
            }
        }
        try {
            lm.requestLocationUpdates(provider, 5_000L, 10f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun flush() {
        synchronized(buffer) { flushLocked() }
    }

    private fun flushLocked() {
        if (buffer.isEmpty()) return
        val copy = ArrayList(buffer)
        buffer.clear()
        lastFlush = System.currentTimeMillis()
        scope.launch { dao.insertAll(copy) }
    }

    override fun onDestroy() {
        Recorder.running = false
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(listener)
        } catch (e: Exception) {
        }
        flush()
        flusher.removeCallbacksAndMessages(null)
        scope.cancel()
        db.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "足迹记录", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "记录足迹时显示的通知"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    companion object {
        const val ACTION_START = "com.daka.app.track.START"
        const val ACTION_STOP = "com.daka.app.track.STOP"
        private const val CHANNEL = "track"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, TrackService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** 记录开关的进程内状态。跨进程/重启后不保留，以服务是否还在跑为准。 */
object Recorder {
    @Volatile
    var running: Boolean = false
}
