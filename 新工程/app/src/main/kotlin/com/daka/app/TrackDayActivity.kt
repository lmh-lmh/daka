package com.daka.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.TrackDao
import com.daka.app.core.map.BaseMap
import com.daka.app.core.track.GpxExporter
import com.daka.app.core.track.TrackGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * 看某一天的轨迹：底图 + 一条线。
 *
 * 单独开一个页面而不是在主地图上叠加，是因为主地图的图层已经三层了（聚类圆、
 * 聚类数字、单点图标），再塞轨迹线要处理 z 序和显隐开关，容易越改越乱。
 */
class TrackDayActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var db: DakaDb
    private lateinit var dao: TrackDao
    private var day: String = ""

    private lateinit var tvDay: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.maplibre.android.MapLibre.getInstance(this)
        setContentView(R.layout.activity_track_day)

        day = intent.getStringExtra(EXTRA_DAY) ?: TrackDao.today()
        db = DakaDb(this)
        dao = TrackDao(db)

        tvDay = findViewById(R.id.tvDay)
        tvSub = findViewById(R.id.tvSub)
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnGpx).setOnClickListener { doExportGpx() }

        tvDay.text = TrackDao.prettyDay(day)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isCompassEnabled = true
                isLogoEnabled = true
                isAttributionEnabled = true
            }
            m.setStyle(Style.Builder().fromJson(BaseMap.styleJson(BaseMap.Kind.ESRI))) { st ->
                style = st
                st.addSource(GeoJsonSource(TRACK_SRC, EMPTY_FC))
                st.addLayer(
                    // 先一条宽的深色描边，再一条细的亮线，这样在浅色影像上也看得清
                    LineLayer(TRACK_CASING, TRACK_SRC).apply {
                        setProperties(
                            PropertyFactory.lineColor(0xFF0B0F14.toInt()),
                            PropertyFactory.lineWidth(9f),
                            PropertyFactory.lineOpacity(0.55f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        )
                    }
                )
                st.addLayer(
                    LineLayer(TRACK_LINE, TRACK_SRC).apply {
                        setProperties(
                            PropertyFactory.lineColor(0xFF43A047.toInt()),
                            PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        )
                    }
                )
                loadTrack()
            }
        }
    }

    private fun loadTrack() {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) { dao.ofDay(day) }
            if (pts.size < 2) {
                tvSub.text = "这一天没有轨迹"
                return@launch
            }
            val fc = TrackGeo.lineOf(pts)
            style?.getSourceAs<GeoJsonSource>(TRACK_SRC)?.setGeoJson(fc)

            val meters = TrackGeo.totalMeters(pts)
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            val s = f.format(java.util.Date(pts.first().recordedAt))
            val e = f.format(java.util.Date(pts.last().recordedAt))
            tvSub.text = "$s – $e · ${TrackGeo.prettyMeters(meters)} · ${pts.size} 个点"
            tvStatus.text = "共 ${pts.size} 个原始点，按 8 米抽稀后画线"

            // 相机框住整条线：取外接矩形的中心，zoom 用包围盒的粗估
            var minLon = pts[0].lon
            var maxLon = pts[0].lon
            var minLat = pts[0].lat
            var maxLat = pts[0].lat
            for (p in pts) {
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
            }
            val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                .include(org.maplibre.android.geometry.LatLng(minLat, minLon))
                .include(org.maplibre.android.geometry.LatLng(maxLat, maxLon))
                .build()
            try {
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 80))
            } catch (e1: Exception) {
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(
                            (minLat + maxLat) / 2, (minLon + maxLon) / 2
                        ), 15.0
                    )
                )
            }
        }
    }

    private fun doExportGpx() {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) { dao.ofDay(day) }
            if (pts.size < 2) {
                toast("这一天没有轨迹，没东西可导")
                return@launch
            }
            val r = withContext(Dispatchers.IO) { GpxExporter.export(this@TrackDayActivity, day, pts) }
            androidx.appcompat.app.AlertDialog.Builder(this@TrackDayActivity)
                .setTitle(if (r.ok) "导出好了" else "没导出成")
                .setMessage(r.message + if (r.ok) "\n\n${r.path}\n\n这个 .gpx 文件可以用两步路、奥维、Strava 等任何支持 GPX 的软件打开。" else "")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart(); mapView.onStart()
    }

    override fun onResume() {
        super.onResume(); mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause(); super.onPause()
    }

    override fun onStop() {
        mapView.onStop(); super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory(); mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        db.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_DAY = "day"
        private const val TRACK_SRC = "track"
        private const val TRACK_CASING = "track-casing"
        private const val TRACK_LINE = "track-line"
        private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
    }
}
