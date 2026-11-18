package com.daka.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.daka.app.core.capture.SystemCamera
import com.daka.app.core.db.DakaDb
import com.daka.app.core.db.Spot
import com.daka.app.core.db.SpotDao
import com.daka.app.core.map.AdminTree
import com.daka.app.core.map.BaseMap
import com.daka.app.core.map.BoundsLayer
import com.daka.app.core.map.MapLevel
import com.daka.app.core.map.RegionAggregator
import com.daka.app.core.map.RegionBackfill
import com.daka.app.core.map.RegionLayer
import com.daka.app.core.map.SpotClusterer
import com.daka.app.core.map.LocationHelper
import com.daka.app.core.map.MarkerLayer
import com.daka.app.core.map.ZoomGuard
import com.daka.app.core.photo.PhotoRepo
import com.daka.app.core.thumb.ThumbLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import android.graphics.PointF

/**
 * 主界面：全屏卫星地图 + 打卡点标记。
 *
 * 设计原则（沿用原工程被用户认可的部分）：
 * - 整屏都是影像，控件尽量少，圆形半透明按钮浮在上面
 * - 底部一个主按钮「在这里打卡」
 * - 点标记从底部升起卡片，卡片里横排显示照片缩略图（用户要求"点标记看到简略照片"）
 *
 * 关于标记数量：这里走 GeoJSON source + symbol layer，不是 AnnotationManager。
 * MapLibre 官方说超过约 50 个标注就该这么换，style layer 能扛十万级。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var db: DakaDb
    private lateinit var dao: SpotDao
    private lateinit var markerLayer: MarkerLayer
    private lateinit var regionLayer: RegionLayer
    private lateinit var regionPanel: RegionPanel
    /** 当前屏幕上的聚合气泡，点它时要用里面的信息 */
    private var lastClusters: List<SpotClusterer.Cluster> = emptyList()
    /** 轻量偏好存储：只用来记住"上次选的相机 App"这类小设置，不碰数据库 */
    private lateinit var prefs: android.content.SharedPreferences
    /** 侧边面板的开关按钮，面板打开时要把它藏起来 */
    private var btnPanelRef: View? = null

    /** 当前分层视图层级（L0 全国 / L1 省内 / L2 市内 / L3 打卡点） */
    private var currentLevel: Int = -1
    /** 面包屑上已下钻到的省 / 市（null 表示还没钻进去） */
    private var drillProvince: AdminTree.Unit? = null
    private var drillCity: AdminTree.Unit? = null
    /** 上一次判层时的 zoom，用于避免 onCameraIdle 里重复刷新 */
    private var lastLevelZoom: Double = -1.0
    /** 上一次真正刷过数据的 层级/省/市，用于判断要不要重算 */
    private var lastRenderedLevel: Int = -99
    private var lastRenderedProv: String? = null
    private var lastRenderedCity: String? = null
    private lateinit var camera: SystemCamera
    private lateinit var photoRepo: PhotoRepo
    private lateinit var locator: LocationHelper

    private var style: Style? = null
    private var spots: List<Spot> = emptyList()
    private var selectedId: Long = -1L
    /** 当前等待结果的动作：新点拍照 or 给已有点追加拍照 */
    private var pendingSpotId: Long = -1L
    private var pendingIsNewSpot = false

    private val main = Handler(Looper.getMainLooper())

    // ---------------- 视图 ----------------
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBreadcrumb: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardName: TextView
    private lateinit var cardMeta: TextView
    private lateinit var cardPhotos: LinearLayout
    private lateinit var cardEmpty: TextView

    // ---------------- 权限 ----------------
    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grant ->
        val ok = grant.values.any { it }
        if (ok) locate() else toast("没有定位权限，只能手动拖动地图选位置")
    }

    /** 系统相机。用户已确认"相机走系统"，不内置 CameraX */
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onCameraResult(result.resultCode)
    }

    /** 从详情页 / 编辑页回来，改了什么都重新读一遍（简单粗暴但不会漏刷） */
    private val regionSpotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            val lon = r.data?.getDoubleExtra(RegionSpotsActivity.RESULT_LON, Double.NaN) ?: Double.NaN
            val lat = r.data?.getDoubleExtra(RegionSpotsActivity.RESULT_LAT, Double.NaN) ?: Double.NaN
            if (lon.isNaN() || lat.isNaN()) return@registerForActivityResult
            // 从点列表"定位"回来：相机移到那个点并放大到街道级
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(lat, lon),
                    MapLevel.zoomForLevel(MapLevel.L3_SPOT),
                )
            )
            // 顺手选中它，弹出照片卡片
            val sid = r.data?.getLongExtra(RegionSpotsActivity.RESULT_SPOT_ID, -1L) ?: -1L
            if (sid > 0) main.postDelayed({ selectSpot(sid) }, 600)
        }
    }

    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == RESULT_OK) refreshAll() }

    private val listLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == RESULT_OK) refreshAll() }

    private val trackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    /** 选备份 zip 来恢复 */
    private val pickRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        toast("正在恢复…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                com.daka.app.core.backup.Backup.import(this@MainActivity, uri)
            }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(if (r.ok) "恢复完成" else "恢复失败")
                .setMessage(r.message)
                .setPositiveButton(if (r.ok) "退出 App" else "知道了") { _, _ ->
                    if (r.ok) {
                        // 必须重启：进程里的 DAO 还连着旧库句柄
                        finishAffinity()
                    }
                }
                .setCancelable(!r.ok)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须先初始化 MapLibre 再创建 MapView，否则 getMapAsync 永远不回调
        // （原工程 MainActivity.kt:176 就是这么做的）
        org.maplibre.android.MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        db = DakaDb(this)
        prefs = getSharedPreferences("daka_prefs", MODE_PRIVATE)
        dao = SpotDao(db)
        markerLayer = MarkerLayer(this)
        regionLayer = RegionLayer(this)
        camera = SystemCamera(this)
        photoRepo = PhotoRepo(this, dao)
        locator = LocationHelper(this)

        mapView = findViewById(R.id.mapView)
        tvHint = findViewById(R.id.tvHint)
        tvStatus = findViewById(R.id.tvStatus)
        tvBreadcrumb = findViewById(R.id.tvBreadcrumb)
        card = findViewById(R.id.cardSpot)
        cardName = findViewById(R.id.cardName)
        cardMeta = findViewById(R.id.cardMeta)
        cardPhotos = findViewById(R.id.cardPhotos)
        cardEmpty = findViewById(R.id.cardEmpty)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            setupMap()
        }
        bindButtons()
        initRegionPanel()
        askLocationIfNeeded()
        // 后台加载行政区表 + 给老数据补算归属（不阻塞界面）
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AdminTree.load(this@MainActivity)
                RegionBackfill.run(dao) { }
            }
            // 补算完成后如果正在分层视图，刷新一次
            if (currentLevel in MapLevel.L0_PROVINCE..MapLevel.L2_DISTRICT) {
                style?.let { refreshRegions(it, currentLevel) }
            }
        }
    }

    // ---------------- 地图 ----------------

    private fun setupMap() {
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = true      // Esri/EOX 的条款都要求保留来源标识
            isAttributionEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        map.setMaxZoomPreference(ZoomGuard.preferenceFor(DEFAULT_CENTER.longitude, DEFAULT_CENTER.latitude))
        map.setMinZoomPreference(2.0)

        map.setStyle(Style.Builder().fromJson(BaseMap.styleJson(BaseMap.Kind.ESRI))) { st ->
            style = st
            markerLayer.clearRegistered()
            installSpotLayer(st)
            installRegionLayer(st)
            reloadSpots()
            // 首次进来把视野放到上次位置，没有就放到用户所在地或中国
            val last = locator.lastKnown()
            val target = last?.let { LatLng(it.latitude, it.longitude) } ?: DEFAULT_CENTER
            map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(target).zoom(if (last != null) 15.0 else 4.0).build()
            applyZoomGuard()
            applyLevel()
        }

        map.addOnMapClickListener { latLng -> onMapClick(latLng) }
        map.addOnMapLongClickListener { latLng ->
            // 长按 = 直接在这个位置建打卡点
            createSpotAt(latLng)
            true
        }
        // 每次移动结束再拉视口数据，避免拖动过程中反复查库
        map.addOnCameraIdleListener {
            applyLevel()
            reloadSpots()
            applyZoomGuard()
        }
    }

    /** 在 style 里装好 GeoJSON source + 三层图层（聚类圆 / 聚类数字 / 单点图标） */
    private fun installSpotLayer(st: Style) {
        // 开聚类：点多了自动收成气泡。图层必须用互斥 filter 分开，
        // 否则聚类产生的"聚合点"没有 icon 属性，会画成一堆空白。
        st.addSource(GeoJsonSource(MarkerLayer.SOURCE_ID, EMPTY_FC, MarkerLayer.clusterOptions()))
        // 自己算的数字聚合气泡：独立 source + 一层 symbol，图标是画好的位图
        st.addSource(GeoJsonSource(MarkerLayer.BUBBLE_SOURCE_ID, EMPTY_FC))
        st.addLayer(
            SymbolLayer(MarkerLayer.BUBBLE_LAYER_ID, MarkerLayer.BUBBLE_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    // 气泡尺寸固定（不随缩放变），因为它承载的是"这里有几个点"这个信息
                    PropertyFactory.iconSize(1.0f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            }
        )

        // 1) 聚类气泡
        st.addLayer(
            CircleLayer(MarkerLayer.CLUSTER_LAYER_ID, MarkerLayer.SOURCE_ID).apply {
                setFilter(Expression.has("point_count"))
                setProperties(
                    // 用橙红系高对比色，不用绿色。
                    // 实测教训：原来是深绿 #2E7D32，正好压在森林/植被上完全看不见，
                    // 表现为"几个点在低层级合并后地图上什么都没有"。
                    PropertyFactory.circleColor(
                        Expression.step(
                            Expression.get("point_count"),
                            Expression.color(0xFFFF8F00.toInt()),
                            Expression.stop(10, Expression.color(0xFFF4511E.toInt())),
                            Expression.stop(50, Expression.color(0xFFD81B60.toInt())),
                            Expression.stop(200, Expression.color(0xFF8E24AA.toInt())),
                        )
                    ),
                    PropertyFactory.circleRadius(
                        Expression.step(
                            Expression.get("point_count"),
                            Expression.literal(18f),
                            Expression.stop(10, Expression.literal(22f)),
                            Expression.stop(50, Expression.literal(27f)),
                            Expression.stop(200, Expression.literal(32f)),
                        )
                    ),
                    // 白描边加粗到 3dp：既是视觉重量，也是和小图标区分开的边界
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                )
            }
        )

        // 2) 气泡里的数字
        st.addLayer(
            SymbolLayer(MarkerLayer.CLUSTER_COUNT_LAYER_ID, MarkerLayer.SOURCE_ID).apply {
                setFilter(Expression.has("point_count"))
                setProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textColor(0xFFFFFFFF.toInt()),
                    // 加一圈深色描边，浅色底图上数字也读得清
                    PropertyFactory.textHaloColor(0xCC000000.toInt()),
                    PropertyFactory.textHaloWidth(1.6f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                )
            }
        )

        // 3) 单独的打卡点（和聚类层互斥）
        st.addLayer(
            SymbolLayer(MarkerLayer.LAYER_ID, MarkerLayer.SOURCE_ID).apply {
                setFilter(Expression.not(Expression.has("point_count")))
                setProperties(
                    // 用表达式读 properties.icon，不要用 "{icon}" 那种老式 token 写法
                    PropertyFactory.iconImage(Expression.get("icon")),
                    // 随缩放自动变小（表达式在渲染层算，不重建位图）
                    PropertyFactory.iconSize(markerLayer.iconSizeExpression()),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    // 允许重叠，必须允许。
                    //
                    // 实测两种方案都不行，记下来避免重犯：
                    // 1. cluster:true —— MapLibre 在这个数据源上不产生 point_count，
                    //    聚类层渲染 feature 数为 0，低层级一个标记都看不见
                    // 2. allowOverlap(false) —— 1000 个点挤在 10km 内时，
                    //    碰撞检测发现一个都放不下，于是**全部隐藏**，比重叠更糟
                    // 现在的做法：允许重叠 + 图标随缩放变小（见 iconSizeExpression），
                    // 缩得越远标记越小，视觉上就不会糊成一片。
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            }
        )
    }

    /**
     * 装行政边界图层（填色 + 描边）。
     *
     * 视觉取舍：填色只给 4% 透明度，仅用来"隐约区分区块"，不喧宾夺主 ——
     * 卫星影像本身信息量已经很大，边界线太重会盖掉地物。
     * 描边用 1.2dp 半透明白，在深浅底色上都能看清。
     *
     * 层级要放在气泡和打卡点**之下**：MapLibre 按 addLayer 顺序决定 z 序，
     * 所以这个方法必须在 installRegionLayer / installSpotLayer 之前调用。
     */
    private fun installBoundsLayer(st: Style) {
        st.addSource(GeoJsonSource(BoundsLayer.SOURCE_ID, BoundsLayer.emptyCollection()))
        st.addLayer(
            org.maplibre.android.style.layers.FillLayer(
                BoundsLayer.FILL_LAYER_ID, BoundsLayer.SOURCE_ID
            ).apply {
                setProperties(
                    PropertyFactory.fillColor(0xFF43A047.toInt()),
                    PropertyFactory.fillOpacity(0.08f),
                )
            }
        )
        st.addLayer(
            org.maplibre.android.style.layers.LineLayer(
                BoundsLayer.LINE_LAYER_ID, BoundsLayer.SOURCE_ID
            ).apply {
                setProperties(
                    // 用暖黄色描边：卫星影像以绿/褐/蓝为主，黄色在三种底色上都能跳出来。
                    // 之前用半透明白（0.55 透明度、1.2dp）实测几乎看不见。
                    PropertyFactory.lineColor(0xFFFFD54F.toInt()),
                    PropertyFactory.lineOpacity(0.95f),
                    PropertyFactory.lineWidth(1.6f),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )
        st.getLayer(BoundsLayer.FILL_LAYER_ID)?.setProperties(
            PropertyFactory.visibility(Property.NONE)
        )
        st.getLayer(BoundsLayer.LINE_LAYER_ID)?.setProperties(
            PropertyFactory.visibility(Property.NONE)
        )
    }

    // ---------------- 分层视图（L0 省 / L1 市 / L2 区县 / L3 打卡点） ----------------

    /**
     * 装分层气泡图层。
     *
     * 和打卡点图层完全分开：这个方法只在 style 加载时调一次，之后只换数据 + 切显隐，
     * 不反复 add/remove layer（MapLibre 上删了再加容易踩坑）。
     */
    private fun installRegionLayer(st: Style) {
        // 行政边界先装：MapLibre 按 addLayer 顺序决定 z 序，先加的在下层。
        // 边界必须在气泡和打卡点之下，否则半透明填色会盖住气泡文字。
        installBoundsLayer(st)

        st.addSource(GeoJsonSource(RegionLayer.SOURCE_ID, regionLayer.emptyCollection()))
        // 底圆：先用它保证"一定看得见"，图标层在上面叠。
        // 这样即使图标因为各种原因没加载出来，也能看到气泡的位置和数量。
        st.addLayer(
            org.maplibre.android.style.layers.CircleLayer(
                RegionLayer.DOT_LAYER_ID, RegionLayer.SOURCE_ID
            ).apply {
                setProperties(
                    PropertyFactory.circleColor(
                        Expression.step(
                            Expression.get("count"),
                            Expression.color(0xFF558B2F.toInt()),
                            Expression.stop(3, Expression.color(0xFF43A047.toInt())),
                            Expression.stop(10, Expression.color(0xFF2E7D32.toInt())),
                            Expression.stop(50, Expression.color(0xFFE8590C.toInt())),
                        )
                    ),
                    PropertyFactory.circleRadius(
                        Expression.step(
                            Expression.get("count"),
                            Expression.literal(14f),
                            Expression.stop(3, Expression.literal(17f)),
                            Expression.stop(10, Expression.literal(21f)),
                            Expression.stop(50, Expression.literal(26f)),
                        )
                    ),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(0xCCFFFFFF.toInt()),
                )
            }
        )

        st.addLayer(
            SymbolLayer(RegionLayer.PILL_LAYER_ID, RegionLayer.SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(1.0f),
                    // 气泡以中心为锚点（和打卡点的针尖锚点不同），这样数量为大时不会偏
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            }
        )
        // 初始全部隐藏，等 applyLevel 决定
        st.getLayer(RegionLayer.DOT_LAYER_ID)?.setProperties(
            PropertyFactory.visibility(Property.NONE)
        )
        st.getLayer(RegionLayer.PILL_LAYER_ID)?.setProperties(
            PropertyFactory.visibility(Property.NONE)
        )

    }

    /**
     * 按当前 zoom 判断该显示哪一层，并切换两套图层的显隐。
     *
     * 只在"层级真的变了"时才刷新数据，避免用户轻微拖动地图就重查库。
     */
    private fun applyLevel() {
        val st = style ?: return
        val zoom = map.cameraPosition.zoom
        val level = MapLevel.levelForZoom(zoom)

        // 层级没变、且不是首次，就不做任何事（省掉大量无谓查询）
        if (level == currentLevel && lastLevelZoom > 0 &&
            kotlin.math.abs(zoom - lastLevelZoom) < 0.6
        ) {
            return
        }
        val levelChanged = level != currentLevel
        currentLevel = level
        lastLevelZoom = zoom

        if (level == MapLevel.L3_SPOT) {
            // L3：显示打卡点，隐藏气泡和边界（边界会干扰看照片和标记）
            setLayerVisible(st, RegionLayer.PILL_LAYER_ID, false)
            setLayerVisible(st, RegionLayer.DOT_LAYER_ID, false)
            setLayerVisible(st, BoundsLayer.FILL_LAYER_ID, false)
            setLayerVisible(st, BoundsLayer.LINE_LAYER_ID, false)
            // 聚类层不用了（见 installSpotLayer 里的说明），保持隐藏
            setLayerVisible(st, MarkerLayer.CLUSTER_LAYER_ID, false)
            setLayerVisible(st, MarkerLayer.CLUSTER_COUNT_LAYER_ID, false)
            setLayerVisible(st, MarkerLayer.LAYER_ID, true)
            hideBreadcrumbIfTop()
            return
        }

        // L0~L2：显示气泡 + 边界，隐藏打卡点（否则会同时看到几十个针和气泡，很乱）
        setLayerVisible(st, RegionLayer.PILL_LAYER_ID, true)
        setLayerVisible(st, RegionLayer.DOT_LAYER_ID, true)
        setLayerVisible(st, BoundsLayer.FILL_LAYER_ID, true)
        setLayerVisible(st, BoundsLayer.LINE_LAYER_ID, true)
        setLayerVisible(st, MarkerLayer.CLUSTER_LAYER_ID, false)
        setLayerVisible(st, MarkerLayer.CLUSTER_COUNT_LAYER_ID, false)
        setLayerVisible(st, MarkerLayer.LAYER_ID, false)

        // 刷新条件：层级变了，或者下钻到了不同的省/市。
        // 后者很关键——从 L1 点"宜春市"进入 L2，zoom 变化过程中 onCameraIdle 可能先在
        // 中间值触发一次，那次 refreshRegions 用的是还没更新的 drill 状态。
        // 把 drill 变化也作为刷新条件，保证状态稳定后一定重算一次。
        val drillChanged = level != lastRenderedLevel ||
            drillProvince?.code != lastRenderedProv ||
            drillCity?.code != lastRenderedCity
        if (levelChanged || drillChanged) {
            refreshRegions(st, level)
            lastRenderedLevel = level
            lastRenderedProv = drillProvince?.code
            lastRenderedCity = drillCity?.code
        }
    }

    private fun setLayerVisible(st: Style, layerId: String, visible: Boolean) {
        try {
            st.getLayer(layerId)?.setProperties(
                PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
            )
        } catch (e: Exception) {
            // 图层可能还没加载完，忽略
        }
    }

    /** 重新算这一层的气泡并推给地图 */
    private fun refreshRegions(st: Style, level: Int) {
        val t = map.cameraPosition.target ?: return
        lifecycleScope.launch {
            val pairs = withContext(Dispatchers.IO) {
                // 先确保行政区表已加载（首次可能还在读 assets）
                if (!AdminTree.isLoaded()) AdminTree.load(this@MainActivity)
                // 优先用"用户点进来的那个省/市"，而不是"当前视野中心落在哪"。
                // 实测教训：下钻后视野会框住整个市，中心点可能落在相邻市（宜春被框住后
                // 中心偏到抚州），于是 L2 显示成抚州（无数据），看着像坏了。
                val centerProvince = drillProvince
                    ?: AdminTree.provinceAt(t.longitude, t.latitude)
                val centerCity = drillCity
                    ?: AdminTree.cityAt(t.longitude, t.latitude)
                val bubbles = RegionAggregator.bubblesFor(dao, level, centerProvince, centerCity)
                bubbles to (centerProvince to centerCity)
            }
            val bubbles = pairs.first
            val density = resources.displayMetrics.density
            val (json, images) = regionLayer.buildGeoJson(bubbles, density)
            // 每次都重新 addImage，不做"已注册就跳过"的优化。
            // 实测教训：MapLibre 的 style 在某些操作后（比如 setStyle / 图层重建）
            // 会把之前 addImage 的图清掉，而我们这边的 registered 集合还记着"已注册"，
            // 于是新气泡的图标永远加不进去 —— 表现就是 L2 层数据对、图层可见、就是不显示。
            // addImage 是幂等的（同 id 覆盖），几百个气泡重复加一次的开销可以忽略。
            for ((id, bmp) in images) {
                if (!bmp.isRecycled) {
                    st.addImage(id, bmp)
                    regionLayer.markRegistered(id)
                }
            }
            (st.getSourceAs<GeoJsonSource>(RegionLayer.SOURCE_ID))?.setGeoJson(json)
            refreshBounds(st, level)
            android.util.Log.d(
                "DakaR",
                "lv=$level prov=${drillProvince?.code} city=${drillCity?.code} "
                    + "bubbles=${bubbles.size} names=${bubbles.map { it.name + it.spotCount }} "
                    + "zoom=${map.cameraPosition.zoom} center=${map.cameraPosition.target}"
            )
            // 终极诊断：查渲染层到底有没有这个图层的 feature
            try {
                val full = android.graphics.RectF(0f, 0f, 2000f, 2000f)
                val rh = map.queryRenderedFeatures(full, RegionLayer.PILL_LAYER_ID)
                val srcF = st.getSourceAs<GeoJsonSource>(RegionLayer.SOURCE_ID)
                    ?.querySourceFeatures(null)
                // 关键诊断：屏幕尺寸、投影点、以及同一 zoom 下的像素密度
                val proj0 = map.projection
                val c0 = map.cameraPosition.target
                val p0 = if (c0 != null) proj0.toScreenLocation(c0) else null
                android.util.Log.d(
                    "DakaGeo",
                    "mapView=${mapView.width}x${mapView.height} " +
                        "displayMetrics=${resources.displayMetrics.widthPixels}x" +
                        "${resources.displayMetrics.heightPixels} d=${resources.displayMetrics.density} " +
                        "centerScreen=$p0 zoom=${map.cameraPosition.zoom}"
                )
                val proj = map.projection
                val pts = bubbles.map {
                    val p = proj.toScreenLocation(LatLng(it.lat, it.lon))
                    "${it.name}@(${p.x.toInt()},${p.y.toInt()})"
                }
                android.util.Log.d(
                    "DakaFinal",
                    "lv=$level rendered=${rh?.size} src=${srcF?.size} "
                        + "screenWH=${mapView.width}x${mapView.height} pts=$pts "
                        + "bearing=${map.cameraPosition.bearing} tilt=${map.cameraPosition.tilt} "
                        + "zoom=${map.cameraPosition.zoom} "
                        + "iconIds=${images.keys}"
                )
            } catch (e: Exception) {
                android.util.Log.d("DakaFinal", "query failed: ${e.message}")
            }
            updateBreadcrumb()
            if (bubbles.isEmpty()) {
                showHint("这一层还没有打卡点")
            }
        }
    }

    /**
     * 点分层气泡 → 下钻。
     *
     * 下钻就是"把相机飞到它的坐标 + 设到下一层的 zoom"，剩下的交给 applyLevel。
     */
    /**
     * 刷新边界数据。
     *
     * 三个维度控制"画哪些"：
     * - 层级：L0 画省界、L1 画市界、L2 画区县界
     * - 范围：L1 只画当前省的市界、L2 只画当前市的区县界
     * - 视口：只画视野附近的，避免把全国 2800 个区县都塞进 source
     *
     * 为什么要按视口裁：区县边界总共 1.3 MB，全国一次性全推给地图会明显卡顿，
     * 而且看不到的部分纯属浪费。
     */
    private fun refreshBounds(st: Style, level: Int) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                when (level) {
                    MapLevel.L0_PROVINCE -> BoundsLayer.geoJson(0, null)
                    MapLevel.L1_CITY -> {
                        val p = drillProvince ?: return@withContext BoundsLayer.emptyCollection()
                        val codes = AdminTree.boundsOfCities()
                            .filter { it.parent == p.code }
                            .map { it.code }
                        BoundsLayer.geoJson(1, codes)
                    }
                    MapLevel.L2_DISTRICT -> {
                        val c = drillCity ?: return@withContext BoundsLayer.emptyCollection()
                        val codes = AdminTree.boundsOfDistricts()
                            .filter { it.parent == c.code }
                            .map { it.code }
                        BoundsLayer.geoJson(2, codes)
                    }
                    else -> BoundsLayer.emptyCollection()
                }
            }
            (st.getSourceAs<GeoJsonSource>(BoundsLayer.SOURCE_ID))?.setGeoJson(json)
            android.util.Log.d(
                "DakaBounds",
                "lv=$level len=${json.length} fillVis=${st.getLayer(BoundsLayer.FILL_LAYER_ID)?.visibility?.value} " +
                    "lineVis=${st.getLayer(BoundsLayer.LINE_LAYER_ID)?.visibility?.value}"
            )
        }
    }

    private fun onRegionTap(bubble: RegionAggregator.Bubble) {
        // 记住钻进了哪个省/市，面包屑要用
        if (bubble.level == MapLevel.L0_PROVINCE) {
            AdminTree.findUnit(bubble.key)?.let { drillProvince = it }
            drillCity = null
        } else if (bubble.level == MapLevel.L1_CITY) {
            AdminTree.findUnit(bubble.key)?.let { drillCity = it }
        }
        val nextLevel = bubble.level + 1
        // 下钻到"下一层气泡的中心"，zoom 用固定值。
        //
        // 为什么不按 bbox 拟合 zoom（实测踩了很久，记下来避免重犯）：
        // MapLibre 的 toScreenLocation / 相机 zoom 内部按 512px 瓦片 × pixelRatio 算，
        // 和 cameraPosition.zoom 报的值在本机（density=2.25）差 2.36 级。
        // 用 bbox 反算 zoom 必然算偏，气泡被甩到屏幕外（日志实测 高安市 y=1731，
        // 而屏幕只有 1504 高）。固定 zoom 笨但绝对可靠。
        val childBox = childrenBox(nextLevel)
        val cx: Double
        val cy: Double
        if (childBox != null) {
            cx = (childBox[0] + childBox[2]) / 2
            cy = (childBox[1] + childBox[3]) / 2
        } else {
            cx = bubble.lon
            cy = bubble.lat
        }
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(cy, cx), MapLevel.zoomForLevel(nextLevel))
        )
    }

    /**
     * 算"下一层的气泡"的外接框。返回 [minLon, minLat, maxLon, maxLat]，没有数据返回 null。
     *
     * 为什么要按气泡算而不是按行政边界算：行政边界包含大片没去过的地方，
     * 按它算视野会把有限的几个气泡挤到角落甚至屏幕外。
     */
    private fun childrenBox(nextLevel: Int): DoubleArray? {
        val bubbles = try {
            RegionAggregator.bubblesFor(dao, nextLevel, drillProvince, drillCity)
                // 必须排除境外网格气泡（key 以 g 开头）。
                // 实测踩过：L2 层的外接框把某个境外的网格（云南边境那一带）算进来，
                // 中心点被拉到 (103.52, 22.48)，用户点"宜春市"却被甩到了云南。
                .filter { !it.key.startsWith("g") }
        } catch (e: Exception) {
            return null
        }
        if (bubbles.isEmpty()) return null
        // 只有一个气泡时，给它一个固定的小范围（约 1 度），否则算不出来
        if (bubbles.size == 1) {
            val b = bubbles[0]
            return doubleArrayOf(b.lon - 0.5, b.lat - 0.5, b.lon + 0.5, b.lat + 0.5)
        }
        var minLon = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        for (b in bubbles) {
            if (b.lon < minLon) minLon = b.lon
            if (b.lon > maxLon) maxLon = b.lon
            if (b.lat < minLat) minLat = b.lat
            if (b.lat > maxLat) maxLat = b.lat
        }
        // 单点时宽高会接近 0，撑开一点避免算出的 zoom 爆掉
        val padLon = ((maxLon - minLon) * 0.3).coerceAtLeast(0.2)
        val padLat = ((maxLat - minLat) * 0.3).coerceAtLeast(0.2)
        return doubleArrayOf(minLon - padLon, minLat - padLat, maxLon + padLon, maxLat + padLat)
    }

    /** 面包屑文案 */
    private fun updateBreadcrumb() {
        val sb = StringBuilder("全国")
        drillProvince?.let { sb.append(" › ").append(it.shortName()) }
        drillCity?.let { sb.append(" › ").append(it.shortName()) }
        tvBreadcrumb.text = sb.toString()
        tvBreadcrumb.visibility =
            if (currentLevel == MapLevel.L3_SPOT) View.GONE else View.VISIBLE
    }

    /** 回到 L3 时把面包屑收起来（打卡点视图不需要它） */
    private fun hideBreadcrumbIfTop() {
        tvBreadcrumb.visibility = View.GONE
    }

    /** 面包屑点击：回到全国视图 */
    private fun onBreadcrumbTap() {
        drillProvince = null
        drillCity = null
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, MapLevel.zoomForLevel(MapLevel.L0_PROVINCE))
        )
    }

    /** 从库里取视口内的点，转 GeoJSON 塞给地图 */
    /** 从别的页面回来时调用：重新取点 + 刷新卡片 + 清掉已删点的选中态 */
    private fun refreshAll() {
        markerLayer.clearRegistered()
        reloadSpots()
        val sel = selectedId
        if (sel > 0) {
            lifecycleScope.launch {
                val s = withContext(Dispatchers.IO) { dao.get(sel) }
                if (s == null) {
                    selectedId = -1L
                    hideCard()
                } else {
                    showCard(s)
                }
            }
        }
    }

    private fun reloadSpots() {
        val st = style ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        // 稍微外扩一点，拖动时边缘不会突然冒点
        val padLon = (bounds.longitudeEast - bounds.longitudeWest) * 0.25
        val padLat = (bounds.latitudeNorth - bounds.latitudeSouth) * 0.25

        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                dao.inBounds(
                    bounds.longitudeWest - padLon,
                    bounds.latitudeSouth - padLat,
                    bounds.longitudeEast + padLon,
                    bounds.latitudeNorth + padLat,
                )
            }
            spots = list
            pushToMap(st)
        }
    }

    private fun pushToMap(st: Style) {
        val density = resources.displayMetrics.density
        val zoom = map.cameraPosition.zoom

        // 先按当前缩放做屏幕空间聚类（纯 Kotlin 算，不依赖 MapLibre 的聚类）
        val clusterResult = SpotClusterer.cluster(
            spots = spots,
            zoom = zoom,
            radiusPx = 70.0,
        )

        // 1) 单点：样式、选中环、徽标都不变
        val (json, images) = markerLayer.buildGeoJson(clusterResult.singles, density, selectedId)
        for ((id, bmp) in images) {
            if (!markerLayer.wasRegistered(id) && !bmp.isRecycled) {
                st.addImage(id, bmp)
                markerLayer.markRegistered(id)
            }
        }
        (st.getSourceAs<GeoJsonSource>(MarkerLayer.SOURCE_ID))?.setGeoJson(json)

        // 2) 聚合气泡
        val (bubJson, bubImages) = markerLayer.buildBubbleGeoJson(clusterResult.clusters, density)
        for ((id, bmp) in bubImages) {
            if (!bmp.isRecycled) st.addImage(id, bmp)
        }
        (st.getSourceAs<GeoJsonSource>(MarkerLayer.BUBBLE_SOURCE_ID))?.setGeoJson(bubJson)

        lastClusters = clusterResult.clusters
        updateStatusLine()
    }

    // ---------------- 交互 ----------------

    private fun bindButtons() {
        findViewById<View>(R.id.btnZoomIn).setOnClickListener {
            map.animateCamera(CameraUpdateFactory.zoomIn())
        }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener {
            map.animateCamera(CameraUpdateFactory.zoomOut())
        }
        findViewById<View>(R.id.btnLocate).setOnClickListener { locate() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<View>(R.id.tvBreadcrumb).setOnClickListener { onBreadcrumbTap() }
        findViewById<View>(R.id.btnAddSpot).setOnClickListener {
            // 屏幕中心对应的坐标建点（比"我的位置"更符合直觉：看到哪就在哪建）
            val c = map.cameraPosition.target
            if (c == null) toast("地图还没准备好，稍等一下") else createSpotAt(c)
        }
        card.findViewById<View>(R.id.cardClose).setOnClickListener { hideCard() }
        card.findViewById<View>(R.id.cardShoot).setOnClickListener {
            if (selectedId > 0) shootFor(selectedId)
        }
        // 长按拍照按钮 = 选相机 App。
        // 不额外加按钮的原因：卡片位置本来就紧，而且"换相机"是低频操作，
        // 短按拍、长按选，符合地图类 App 的常见做法（长按出更多选项）。
        card.findViewById<View>(R.id.cardShoot).setOnLongClickListener {
            if (selectedId > 0) {
                chooseCameraApp(selectedId)
                true
            } else {
                false
            }
        }
        card.findViewById<View>(R.id.cardDetail).setOnClickListener {
            if (selectedId > 0) openDetail(selectedId)
        }
    }

    /** 点击地图：先看是不是聚类气泡，再看有没有点到标记，都没有就收卡片 */
    /**
     * 点地图：先看是不是聚类气泡，再看有没有点到打卡点。
     *
     * 命中判定踩过两个坑，都在真机上验证过：
     * 1. 不能只查点击的那一个像素。缩到低层级时标记只有十几像素，手指点不准。
     * 2. 标记图标的锚点是 ICON_ANCHOR_BOTTOM，**地理坐标落在针尖**，针身向上画。
     *    所以针身中上部在 MapLibre 的命中测试里是空的，用户看着点在针上却没反应。
     *    解决办法：以屏幕点为中心取一个偏上的矩形（覆盖针身），并做了两轮——
     *    先小范围（精确点针尖），再大范围（兜住点在针身上的情况）。
     */
    private fun onMapClick(latLng: LatLng): Boolean {
        val screen = map.projection.toScreenLocation(latLng)
        val d = resources.displayMetrics.density

        // 0) 分层气泡（L0~L2）。放在最前面，因为这几层里打卡点是隐藏的
        if (currentLevel != MapLevel.L3_SPOT) {
            val touch = 24 * resources.displayMetrics.density
            val rect = android.graphics.RectF(
                screen.x - touch, screen.y - touch,
                screen.x + touch, screen.y + touch,
            )
            val regionHits = map.queryRenderedFeatures(rect, RegionLayer.PILL_LAYER_ID)
            val hit = regionHits?.firstOrNull()
            if (hit != null) {
                val key = hit.getStringProperty("key") ?: ""
                val name = hit.getStringProperty("name") ?: ""
                val lvl = hit.getNumberProperty("level")?.toInt() ?: currentLevel
                val cnt = hit.getNumberProperty("count")?.toInt() ?: 0
                val geo = hit.geometry()
                if (geo is org.maplibre.geojson.Point) {
                    onRegionTap(
                        RegionAggregator.Bubble(
                            key = key, name = name,
                            lon = geo.longitude(), lat = geo.latitude(),
                            spotCount = cnt, photoCount = 0,
                            coverRelPath = "", level = lvl,
                        )
                    )
                    return true
                }
            }
        }

        // 1) 数字聚合气泡：点一下自动放大到能散开
        run {
            val touch = 28 * resources.displayMetrics.density
            val rect = android.graphics.RectF(
                screen.x - touch, screen.y - touch,
                screen.x + touch, screen.y + touch,
            )
            val hit = map.queryRenderedFeatures(rect, MarkerLayer.BUBBLE_LAYER_ID)?.firstOrNull()
            if (hit != null) {
                val lon = hit.getNumberProperty("lon")?.toDouble()
                val lat = hit.getNumberProperty("lat")?.toDouble()
                val cnt = hit.getNumberProperty("count")?.toInt() ?: 0
                if (lon != null && lat != null) {
                    expandClusterAt(lon, lat, cnt)
                    return true
                }
            }
        }

        // 1b) MapLibre 内置聚类（已停用，保留兜底）
        val clusterHits = map.queryRenderedFeatures(screen, MarkerLayer.CLUSTER_LAYER_ID)
        clusterHits?.firstOrNull()?.let {
            expandCluster(it)
            return true
        }

        // 2) 打卡点：两轮查询
        val rounds = arrayOf(
            // 第一轮：手指落在针尖附近（小范围精确）
            android.graphics.RectF(screen.x - 20 * d, screen.y - 20 * d, screen.x + 20 * d, screen.y + 20 * d),
            // 第二轮：手指落在针身上（矩形往上延伸约 70dp，覆盖整个图标高度）
            android.graphics.RectF(screen.x - 32 * d, screen.y - 72 * d, screen.x + 32 * d, screen.y + 24 * d),
        )
        for (rect in rounds) {
            val hits = map.queryRenderedFeatures(rect, MarkerLayer.LAYER_ID)
            if (hits != null) {
                for (f in hits) {
                    val id = f.getNumberProperty("id")?.toLong() ?: f.id()?.toLongOrNull() ?: continue
                    if (id > 0) {
                        selectSpot(id)
                        return true
                    }
                }
            }
        }

        hideCard()
        return false
    }

    /**
     * 点聚合气泡：放大到能把这一簇散开的级别。
     *
     * 怎么定"散开的级别"：簇里点越多、越密集，需要的放大倍数越大。
     * 这里用一个简单的启发式——每翻一级世界宽翻倍，屏幕距离随 zoom 线性放大，
     * 所以从当前 zoom 往上加 log2(count) 级左右，能把 N 个点拉开。
     * 最后再 clamp 到 L3 区间，保证一定落到"看得见单点"的层级。
     */
    private fun expandClusterAt(lon: Double, lat: Double, count: Int) {
        val cur = map.cameraPosition.zoom
        // count=2 加约 1 级，count=4 加 2 级，count=16 加 4 级……
        val bump = Math.log(count.coerceAtLeast(2).toDouble()) / Math.log(2.0)
        val target = (cur + bump * 0.75).coerceIn(
            MapLevel.zoomForLevel(MapLevel.L3_SPOT),
            ZoomGuard.preferenceFor(lon, lat),
        )
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), target)
        )
        showHint("这里 $count 个点，放大看看")
    }

    /**
     * 展开聚类。
     *
     * `getClusterExpansionZoom` 要用当前 source 的实例去问，因为聚类是 MapLibre
     * 在 native 侧算的，展开级别只有它知道。注意 `cluster_id` 不能跨 setGeoJson
     * 持久化——每次数据变了重新查，别把旧的 Feature 存起来后直接用。
     */
    private fun expandCluster(cluster: org.maplibre.geojson.Feature) {
        val src = style?.getSourceAs<GeoJsonSource>(MarkerLayer.SOURCE_ID) ?: return
        try {
            val nextZoom = src.getClusterExpansionZoom(cluster)
            val pt = cluster.geometry() as? org.maplibre.geojson.Point ?: return
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(pt.latitude(), pt.longitude()),
                    nextZoom.toDouble(),
                )
            )
        } catch (e: Exception) {
            // 数据刚被 setGeoJson 换掉时可能抛 CLUSTER_NOT_FOUND，忽略即可，
            // 下一次点击就正常了
            toast("这个气泡刚更新，再点一下")
        }
    }

    private fun selectSpot(id: Long) {
        selectedId = id
        style?.let { pushToMap(it) }
        val s = spots.firstOrNull { it.id == id } ?: return
        showCard(s)
    }

    // ---------------- 卡片 ----------------

    private fun showCard(s: Spot) {
        cardName.text = s.name
        cardMeta.text = buildString {
            append(String.format("%.6f, %.6f", s.lon, s.lat))
            if (s.photoCount > 0) append(" · ${s.photoCount} 张照片")
            if (s.category.isNotEmpty()) append(" · ${s.category}")
        }
        cardVisibility(true)
        loadCardPhotos(s)
    }

    private fun loadCardPhotos(s: Spot) {
        cardPhotos.removeAllViews()
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) { dao.photosOf(s.id) }
            if (photos.isEmpty()) {
                cardEmpty.visibility = View.VISIBLE
                return@launch
            }
            cardEmpty.visibility = View.GONE
            val px = (72 * resources.displayMetrics.density).toInt()
            for ((i, p) in photos.take(12).withIndex()) {
                val iv = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(px, px).apply {
                        marginEnd = (6 * resources.displayMetrics.density).toInt()
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0x22FFFFFF)
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, PhotoViewerActivity::class.java)
                                .putExtra(PhotoViewerActivity.EXTRA_SPOT_ID, s.id)
                                .putExtra(PhotoViewerActivity.EXTRA_INDEX, i)
                        )
                    }
                }
                cardPhotos.addView(iv)
                ThumbLoader.load(this@MainActivity, p, px) { bmp ->
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun hideCard() {
        cardVisibility(false)
        if (selectedId > 0) {
            selectedId = -1L
            style?.let { pushToMap(it) }
        }
    }

    private fun cardVisibility(show: Boolean) {
        card.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------- 建点 & 拍照 ----------------

    /** 在里面给定坐标建一个打卡点，然后立刻问要不要拍照 */
    private fun createSpotAt(latLng: LatLng) {
        val name = "打卡点 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}"
        pendingIsNewSpot = true
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                // 建点时就先算好行政区，避免还要等下次启动的批量回填
                val a = if (AdminTree.isLoaded()) AdminTree.assign(latLng.longitude, latLng.latitude) else null
                dao.insert(
                    Spot(
                        lon = latLng.longitude,
                        lat = latLng.latitude,
                        name = name,
                        provinceCode = a?.provinceCode ?: "",
                        provinceName = a?.provinceName ?: "",
                        cityCode = a?.cityCode ?: "",
                        cityName = a?.cityName ?: "",
                        districtCode = a?.districtCode ?: "",
                        districtName = a?.districtName ?: "",
                    )
                )
            }
            if (id <= 0) {
                toast("建点失败")
                return@launch
            }
            pendingSpotId = id
            reloadSpots()
            selectSpot(id)
            shootFor(id)
        }
    }

    /**
     * 弹出相机 App 选择器。
     *
     * 背景：`ACTION_IMAGE_CAPTURE` 默认会走系统选定的组件，小米上是
     * `com.android.camera.OneShotCamera` —— 被裁剪过的精简拍照界面，功能少很多。
     * 同一台设备上往往还有完整的相机 App（甚至第三方相机），让用户自己选。
     */
    private fun chooseCameraApp(spotId: Long) {
        val s = spots.firstOrNull { it.id == spotId } ?: return
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { camera.listCameraApps() }
            if (apps.isEmpty()) {
                toast("这台设备上没有可用的相机 App")
                return@launch
            }
            val labels = apps.map { app ->
                // 给精简版加个标注，用户一看就知道哪个功能更全
                if (app.isLiteVariant()) "${app.label}（精简版）" else app.label
            }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("用哪个相机拍")
                .setItems(labels) { _, which ->
                    val app = apps[which]
                    // 记住选择，下次短按就直接用它（用户明确表达过偏好就别每次问）
                    prefs.edit().putString(PREF_CAMERA_APP, app.packageName + "/" + app.activityName).apply()
                    shootWith(s, app)
                }
                .setNeutralButton("恢复默认") { _, _ ->
                    prefs.edit().remove(PREF_CAMERA_APP).apply()
                    toast("已恢复系统默认相机")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 用指定的相机 App 拍 */
    private fun shootWith(s: Spot, app: SystemCamera.CameraApp) {
        val intent = camera.newCaptureIntentWith(s.name, app) ?: run {
            toast("准备拍照位置失败")
            return
        }
        pendingSpotId = s.id
        try {
            takePicture.launch(intent)
        } catch (e: Exception) {
            camera.abortPending()
            toast("打不开 ${app.label}：${e.message}")
        }
    }

    /** 给某个点拍照：走系统相机 */
    private fun shootFor(spotId: Long) {
        val s = spots.firstOrNull { it.id == spotId }
            ?: run {
                toast("找不到这个打卡点")
                return
            }
        if (!camera.hasCameraApp()) {
            toast("这台设备上没有相机 App")
            return
        }
        // 如果用户之前长按选过相机，就直接用它；否则走系统默认
        val saved = prefs.getString(PREF_CAMERA_APP, null)
        val intent = if (saved != null && saved.contains("/")) {
            val parts = saved.split("/", limit = 2)
            camera.newCaptureIntentWith(
                s.name,
                SystemCamera.CameraApp(parts[0], parts[1], parts[0]),
            )
        } else {
            camera.newCaptureIntent(s.name)
        } ?: run {
            toast("准备拍照位置失败")
            return
        }
        pendingSpotId = spotId
        try {
            takePicture.launch(intent)
        } catch (e: Exception) {
            camera.abortPending()
            toast("打不开相机：${e.message}")
        }
    }

    private fun onCameraResult(resultCode: Int) {
        if (resultCode != RESULT_OK) {
            // 用户取消：必须把 MediaStore 里刚建的空记录删掉，否则相册会留 0 字节坏图
            camera.abortPending()
            return
        }
        val shot = camera.validateAndConsume()
        if (shot == null) {
            toast("照片没拍成功（文件太小或读不出来），请重拍")
            return
        }
        val spotId = pendingSpotId
        if (spotId <= 0) {
            toast("照片拍好了，但找不到对应的打卡点")
            return
        }
        val s = spots.firstOrNull { it.id == spotId }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                photoRepo.attachFromCamera(spotId, shot, s?.lon ?: 0.0, s?.lat ?: 0.0)
            }
            if (r.ok) {
                toast("已保存到相册 DCIM/打卡/")
                reloadSpots()
                spots.firstOrNull { it.id == spotId }?.let { showCard(it) }
            } else {
                toast(r.message.ifEmpty { "保存失败" })
            }
        }
    }

    // ---------------- 定位 & 缩放保护 ----------------

    private fun askLocationIfNeeded() {
        if (locator.hasPermission()) {
            locate()
            return
        }
        askLocation.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun locate() {
        if (!locator.hasPermission()) {
            askLocationIfNeeded()
            return
        }
        tvStatus.text = "定位中…"
        locator.requestOnce(
            onLocation = { loc ->
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.0)
                )
                applyZoomGuard()
            },
            onFail = { msg -> toast(msg) },
        )
    }

    /**
     * 按位置下调最大缩放级别。
     *
     * 实测宜春等 6 个江西地市只有 z18 有影像，再往上就是 2521 字节的灰底占位图。
     * 与其让用户放大到一片灰，不如停在他能看到的最清楚那一级，并明确提示。
     */
    private fun applyZoomGuard() {
        val t = map.cameraPosition.target ?: return
        // 注意用 preferenceFor 而不是 maxZoomFor：maxZoomFor 给的是最深瓦片层级，
        // 地图 zoom 要比它小 1 级才刚好不请求下一级瓦片。真机 z18 白屏就是这么来的。
        val maxZoom = ZoomGuard.preferenceFor(t.longitude, t.latitude)
        map.setMaxZoomPreference(maxZoom)
        val region = ZoomGuard.regionNameFor(t.longitude, t.latitude)
        if (region != null && map.cameraPosition.zoom >= maxZoom - 0.4) {
            // 对用户说"瓦片层级"而不是"地图 zoom"，因为屏幕上根本没有 zoom 这个词
            val tileMax = ZoomGuard.maxZoomFor(t.longitude, t.latitude)
            showHint("${region}这一片的卫星影像最清楚就到 ${tileMax} 级，再放大只是把图拉模糊")
        }
    }

    private fun showHint(text: String) {
        tvHint.text = text
        tvHint.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(HINT_TOKEN)
        main.postAtTime({ tvHint.visibility = View.GONE }, HINT_TOKEN, System.currentTimeMillis() + 4000)
    }

    private fun updateStatusLine() {
        val t = map.cameraPosition.target ?: return
        val zoom = String.format(java.util.Locale.US, "%.1f", map.cameraPosition.zoom)
        val n = spots.size
        tvStatus.text = "${String.format("%.4f, %.4f", t.latitude, t.longitude)} · z$zoom · $n 个点"
    }

    // ---------------- 其它 ----------------

    private fun showMenu() {
        val items = arrayOf(
            "我的打卡点列表",
            "我的足迹",
            "切换底图（全球概览）",
            "回到我的位置",
            "我的成就",
            "备份数据（导出 zip）",
            "从备份恢复",
            "重算行政区归属",
            "灌 1000 个测试点（性能用）",
            "清空测试点",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openList()
                    1 -> openTrack()
                    2 -> switchBaseMap()
                    3 -> locate()
                    4 -> openAchievements()
                    5 -> doBackup()
                    6 -> askRestore()
                    7 -> recomputeRegions()
                    8 -> seedTestData()
                    9 -> clearTestData()
                }
            }
            .show()
    }

    private fun doBackup() {
        toast("正在打包…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                // 先把 WAL 落进主库，否则最近写的点在 -wal 里，备份出来是旧的
                com.daka.app.core.backup.Backup.checkpoint(db)
                com.daka.app.core.backup.Backup.export(this@MainActivity)
            }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(if (r.ok) "备份好了" else "备份失败")
                .setMessage(r.message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun askRestore() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("从备份恢复")
            .setMessage(
                "选一个之前导出的 zip。\n\n" +
                    "⚠️ 这会用备份里的数据**替换**现在手机上的全部打卡点、照片记录和足迹，" +
                    "现在的数据会被覆盖（旧的数据库会留一份 daka.db.bak 以备万一）。" +
                    "照片原图在相册里，不受影响。"
            )
            .setNegativeButton("算了", null)
            .setPositiveButton("选个 zip") { _, _ -> pickRestore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            .show()
    }

    /**
     * 初始化侧边地区列表。
     *
     * 点某一项的行为：飞到该地区 + 钻到下一层。所以点"江西省"会直接进省内看市，
     * 点"宜春市"会直接进市看区县 —— 和点地图上的气泡效果一致。
     */
    private fun initRegionPanel() {
        regionPanel = RegionPanel(this, findViewById(android.R.id.content))
        regionPanel.onPick = { item ->
            if (item.level >= MapLevel.L2_DISTRICT) {
                // 区县级（最细一级）：不进地图，直接打开该区县的点列表。
                // 用户在那里能看到每个点的位置和缩略图，并能「定位」或「导航」。
                // 这是"选到区县后还要能看到具体各个标志点"的落点。
                regionSpotLauncher.launch(
                    Intent(this@MainActivity, RegionSpotsActivity::class.java)
                        .putExtra(RegionSpotsActivity.EXTRA_CODE, item.code)
                        .putExtra(RegionSpotsActivity.EXTRA_NAME, item.name)
                        .putExtra(RegionSpotsActivity.EXTRA_LEVEL, 2)
                )
            } else {
                // 记录钻进的位置，面包屑和分层刷新都靠它
                when (item.level) {
                    MapLevel.L0_PROVINCE -> {
                        AdminTree.findProvince(item.code)?.let { drillProvince = it }
                        drillCity = null
                    }
                    MapLevel.L1_CITY -> {
                        AdminTree.findUnit(item.code)?.let { drillCity = it }
                    }
                }
                val nextLevel = item.level + 1
                val zoom = MapLevel.zoomForLevel(nextLevel)
                // 有打卡点时飞到"该地区第一个打卡点的位置"，而不是行政区中心。
                // 实测：高安市外接框中心 (115.30,28.34) 与实际打卡点 (115.41,28.23)
                // 差约 12 km，z14 视野只有几公里，飞中心会看到一片没有点的空地。
                val target = if (item.count > 0) {
                    val first = dao.spotsOfRegion(item.level, item.code).firstOrNull()
                    if (first != null) LatLng(first.lat, first.lon)
                    else LatLng(item.lat, item.lon)
                } else {
                    LatLng(item.lat, item.lon)
                }
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
                toast("已定位到 ${item.name}")
                // 点完重开面板并切到下一级，形成"省 → 市 → 区县"的逐级下钻
                main.postDelayed({
                    val lv = currentLevel.coerceIn(0, 2)
                    regionPanel.refresh(
                        lv,
                        drillProvince?.code,
                        drillCity?.code,
                        countsForLevel(lv),
                    )
                    regionPanel.show()
                    btnPanelRef?.visibility = View.GONE
                }, 700)
            }
        }
        val btnPanel = findViewById<View>(R.id.btnRegionPanel)
        btnPanelRef = btnPanel
        btnPanel.setOnClickListener {
            regionPanel.toggle()
            // 面板打开时把开关按钮自己藏起来：它就在面板左上角，
            // 不藏会被面板盖住一部分，看着像面板上长了个奇怪的图标
            btnPanel.visibility = if (regionPanel.isVisible()) View.GONE else View.VISIBLE
            if (regionPanel.isVisible()) refreshRegionPanel()
        }
        // 面板自己收起时（点遮罩/✕）也要把开关恢复
        regionPanel.onHidden = { btnPanel.visibility = View.VISIBLE }

        // 返回上一级：区县 → 市 → 省 → 全国，逐级退出
        regionPanel.onBack = { goUpOneLevel() }
        // 一步回到全国，不管现在钻到多深
        regionPanel.onHome = { goToCountry() }
    }

    /**
     * 一步回到全国视图。
     *
     * 和 goUpOneLevel 的区别：这个不管当前钻到多深，直接清空 drill 状态、相机拉到全国。
     * 用户从区县想回全国时不用连点三次返回。
     */
    private fun goToCountry() {
        drillProvince = null
        drillCity = null
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                DEFAULT_CENTER, MapLevel.zoomForLevel(MapLevel.L0_PROVINCE)
            )
        )
        main.postDelayed({
            val lv = currentLevel.coerceIn(0, 2)
            regionPanel.refresh(lv, null, null, countsForLevel(lv))
            regionPanel.show()
            btnPanelRef?.visibility = View.GONE
        }, 700)
    }

    /**
     * 面板上的"返回上一级"。
     *
     * 退一级要做两件相关的事：
     * 1. 把 drill 状态往上收一层（区县→市→省→全国）
     * 2. 让地图跟着回到对应层级，否则面板列的是上一级、地图还停在下一级，对不上
     *
     * 顺序上先改状态再动地图：applyLevel 依赖 drill 状态决定列什么，
     * 反过来会在动画过程中用旧状态刷一次面板，看到一闪而过的错误列表。
     */
    private fun goUpOneLevel() {
        val st = style ?: return
        when {
            // 在区县层 → 退回市
            drillCity != null -> {
                drillCity = null
                val p = drillProvince
                val zoom = MapLevel.zoomForLevel(MapLevel.L1_CITY)
                val target = if (p != null) LatLng(p.lat, p.lon) else DEFAULT_CENTER
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            }
            // 在市层 → 退回全国
            drillProvince != null -> {
                goToCountry()
                return
            }
            else -> return
        }
        // 动画走完后按新层级刷新面板（700ms 是实测相机动画大致时长）
        main.postDelayed({
            val lv = currentLevel.coerceIn(0, 2)
            regionPanel.refresh(lv, drillProvince?.code, drillCity?.code, countsForLevel(lv))
            regionPanel.show()
            btnPanelRef?.visibility = View.GONE
        }, 700)
    }

    /** 供 onPick 直接取某层的点数统计（同步、轻量，几十条聚合是毫秒级） */
    private fun countsForLevel(level: Int): Map<String, Int> {
        val map = HashMap<String, Int>()
        try {
            val rows = when (level) {
                0 -> dao.aggByProvince()
                1 -> {
                    val p = drillProvince
                    if (p == null) dao.aggByProvince() else dao.aggByCity(p.code)
                }
                else -> {
                    val c = drillCity
                    if (c == null) {
                        val p = drillProvince
                        if (p == null) dao.aggByProvince() else dao.aggByCity(p.code)
                    } else {
                        dao.aggByDistrict(c.code)
                    }
                }
            }
            for (r in rows) map[r.key] = r.spotCount
        } catch (e: Exception) {
        }
        return map
    }

    /**
     * 刷新侧边列表内容。
     *
     * 点数统计走后台查询（全国 34 个省的聚合是毫秒级，但别占主线程）。
     */
    private fun refreshRegionPanel() {
        val level = currentLevel.coerceIn(0, 2)
        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val map = HashMap<String, Int>()
                try {
                    val rows = when (level) {
                        0 -> dao.aggByProvince()
                        1 -> {
                            val p = drillProvince
                            if (p == null) dao.aggByProvince() else dao.aggByCity(p.code)
                        }
                        else -> {
                            val c = drillCity
                            if (c == null) {
                                val p = drillProvince
                                if (p == null) dao.aggByProvince() else dao.aggByCity(p.code)
                            } else {
                                dao.aggByDistrict(c.code)
                            }
                        }
                    }
                    for (r in rows) map[r.key] = r.spotCount
                } catch (e: Exception) {
                    // 查询失败就给空统计，列表仍然可用（只是不显示数量）
                }
                map
            }
            regionPanel.refresh(level, drillProvince?.code, drillCity?.code, counts)
        }
    }

    /**
     * 重算所有点的行政区归属。
     *
     * 什么时候需要它：归类算法升级后（比如从"最近中心"换成"多边形包含"），
     * 老数据是用旧算法算的，必须强制重算。平时不需要手动点。
     */
    private fun recomputeRegions() {
        toast("正在重算…")
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                dao.resetAllRegions()
                if (!AdminTree.isLoaded()) AdminTree.load(this@MainActivity)
                RegionBackfill.run(dao) { }
            }
            markerLayer.clearRegistered()
            reloadSpots()
            style?.let { if (currentLevel != MapLevel.L3_SPOT) refreshRegions(it, currentLevel) }
            toast("已重算 $n 个点")
        }
    }

    private fun openAchievements() {
        startActivity(Intent(this, AchievementsActivity::class.java))
    }

    private fun openTrack() {
        trackLauncher.launch(Intent(this, TrackActivity::class.java))
    }

    // ---------------- 性能自测数据 ----------------

    /**
     * 灌一批测试点，用来在真机上验"1000 个点会不会卡"。
     *
     * 全部落在当前视野中心附近（约 ±0.05 度，几公里范围），这样能同时测到
     * 聚类、视口裁剪、图标缓存三条路径。名字带 `[测试]` 前缀，方便一键清掉。
     */
    private fun seedTestData() {
        val t = map.cameraPosition.target ?: return
        toast("正在生成 1000 个测试点…")
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                val rnd = java.util.Random(42)
                val cats = listOf("美食", "风景", "旅行", "日常")
                val shapes = com.daka.app.core.map.MarkerShape.ALL
                var c = 0
                db.writableDatabase.beginTransaction()
                try {
                    for (i in 0 until 1000) {
                        val lon = t.longitude + (rnd.nextDouble() - 0.5) * 0.1
                        val lat = t.latitude + (rnd.nextDouble() - 0.5) * 0.1
                        // 必须算行政区，否则这些点在分层视图里会被当成"境外"过滤掉，
                        // 地图上看起来就是"灌了 1000 个点但一个都不显示"
                        val a = AdminTree.assign(lon, lat)
                        val s = Spot(
                            lon = lon,
                            lat = lat,
                            name = "[测试] 点 $i",
                            provinceCode = a?.provinceCode ?: "",
                            provinceName = a?.provinceName ?: "",
                            cityCode = a?.cityCode ?: "",
                            cityName = a?.cityName ?: "",
                            districtCode = a?.districtCode ?: "",
                            districtName = a?.districtName ?: "",
                            category = cats[rnd.nextInt(cats.size)],
                            rating = rnd.nextInt(6),
                            favorite = rnd.nextInt(10) == 0,
                            shape = shapes[rnd.nextInt(shapes.size)],
                            color = com.daka.app.core.map.MarkerShape.PALETTE[
                                rnd.nextInt(com.daka.app.core.map.MarkerShape.PALETTE.size)
                            ],
                            size = 0.8f + rnd.nextFloat() * 0.6f,
                        )
                        if (dao.insert(s) > 0) c++
                    }
                    db.writableDatabase.setTransactionSuccessful()
                } finally {
                    db.writableDatabase.endTransaction()
                }
                c
            }
            markerLayer.clearRegistered()
            reloadSpots()
            showHint("已灌入 $n 个测试点，试试拖动和缩放")
        }
    }

    private fun clearTestData() {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                db.writableDatabase.delete("spot", "name LIKE ?", arrayOf("[测试]%"))
            }
            markerLayer.clearRegistered()
            selectedId = -1L
            hideCard()
            reloadSpots()
            toast("已清掉 $n 个测试点")
        }
    }

    private fun openList() {
        listLauncher.launch(Intent(this, SpotListActivity::class.java))
    }

    private fun openDetail(id: Long) {
        detailLauncher.launch(
            Intent(this, SpotDetailActivity::class.java)
                .putExtra(SpotDetailActivity.EXTRA_SPOT_ID, id)
        )
    }

    private fun switchBaseMap() {        map.setStyle(Style.Builder().fromJson(BaseMap.styleJson(BaseMap.Kind.EOX))) { st ->
            style = st
            markerLayer.clearRegistered()
            installSpotLayer(st)
            reloadSpots()
            showHint("已切到全球概览底图（只到 17 级）")
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- 生命周期 ----------------

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
        main.removeCallbacksAndMessages(null)
        ThumbLoader.clearMem()
        mapView.onDestroy()
        db.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        /** 没有定位时先给个中国范围，比空地图友好 */
        private val DEFAULT_CENTER = LatLng(28.68, 115.86)
        private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
        private val HINT_TOKEN = Any()
        /** 记住的相机 App，格式 "包名/Activity名" */
        private const val PREF_CAMERA_APP = "camera_app"
    }
}
