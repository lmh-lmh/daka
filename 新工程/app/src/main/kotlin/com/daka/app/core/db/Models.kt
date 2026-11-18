package com.daka.app.core.db

/**
 * 打卡点。
 *
 * 设计要点：
 * - 一个地点 = 一个打卡点，可以反复追加照片（用户确认的方案）
 * - 样式不预设"样式包"，而是三个独立维度（形状/颜色/大小）+ 自定义图标 + 文字 emoji，
 *   因为用户明确要"多样化、可自定义"。优先级：iconUri > text > shape+color
 * - 照片只存相对路径，不存绝对路径（相册目录可能变，比如换手机恢复备份）
 */
data class Spot(
    val id: Long = 0,
    var lon: Double,
    var lat: Double,
    var name: String,
    var note: String = "",
    var category: String = "未分类",
    var rating: Int = 0,
    var favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    /** 形状名，见 MarkerShape */
    var shape: String = "pin",
    /** 颜色 #AARRGGBB */
    var color: Int = 0xFF2E7D32.toInt(),
    /** 相对大小，1.0 是标准 */
    var size: Float = 1.0f,
    /** 自定义图标：App 私有目录里的文件名，空表示不用自定义图标 */
    var iconFile: String = "",
    /** 文字/emoji 标记，非空时优先于形状绘制 */
    var text: String = "",
    /**
     * 行政区归属（v2 加入）。境外点这 6 个字段全空，分层视图会按经纬度网格聚合。
     * 存在点上而不是每次现算：分层视图要按省/市 GROUP BY，现算就没法用 SQL 聚合了。
     */
    var provinceCode: String = "",
    var provinceName: String = "",
    var cityCode: String = "",
    var cityName: String = "",
    var districtCode: String = "",
    var districtName: String = "",
) {
    /** 照片张数，由 DAO 填充，不落库 */
    var photoCount: Int = 0
    /** 封面照片相对路径，由 DAO 填充，不落库 */
    var coverPath: String = ""
}

/**
 * 一张照片。
 *
 * uri 存的是 MediaStore 的 content:// 串或者是公共相册相对路径。
 * 之所以两个都存：content:// 在照片被移动后会失效，相对路径更稳；
 * 但读取时 content:// 更快，所以优先用 uri，失败再按路径找。
 */
data class Photo(
    val id: Long = 0,
    val spotId: Long,
    /** content://media/external/images/media/12345 */
    val uri: String,
    /** DCIM/打卡/西湖/20261112_153012.jpg */
    val relPath: String,
    /** 缩略图文件名（App 私有目录），空表示还没生成 */
    var thumbFile: String = "",
    /** 原始文件字节数，用来判断是不是被压成了缩略图 */
    val bytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** 拍摄时的经纬度，可能等于打卡点位置，也可能没有（相机没写 GPS） */
    val lon: Double? = null,
    val lat: Double? = null,
    val takenAt: Long = System.currentTimeMillis(),
    /** 去重用 */
    val sha256: String = "",
)
