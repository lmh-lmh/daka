package com.daka.app.core.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 定位。用系统 LocationManager，不引 Google Play Services
 * （国内机型大多没有 GMS，引了也是白引）。
 *
 * 策略：先要 GPS_PROVIDER 的 lastKnownLocation 立即显示（"上次位置"），
 * 再开一次定位请求等真实位置。定位失败不阻塞主流程。
 */
class LocationHelper(private val ctx: Context) {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** 取一个最近的位置，取不到返回 null。优先 GPS，退回网络定位。 */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: Exception) {
                null
            }
            if (l != null && (best == null || l.time > best!!.time)) best = l
        }
        return best
    }

    /**
     * 请求一次定位。成功回调 onLocation，超时或失败回调 onFail（都只在主线程）。
     * 拿到第一个点就自动停，不做持续追踪（轨迹功能在阶段 3 单独做）。
     */
    @SuppressLint("MissingPermission")
    fun requestOnce(onLocation: (Location) -> Unit, onFail: (String) -> Unit) {
        if (!hasPermission()) {
            onFail("没有定位权限")
            return
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                onFail("手机的定位开关没打开")
                return
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try {
                    lm.removeUpdates(this)
                } catch (e: Exception) {
                }
                onLocation(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            // 15 秒还没点就放弃，用 lastKnown 兜底
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                try {
                    lm.removeUpdates(listener)
                } catch (e: Exception) {
                }
            }, 15_000L)
        } catch (e: Exception) {
            onFail("定位请求失败：${e.message}")
        }
    }
}
