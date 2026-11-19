package com.bgoom.app.idphoto

data class IdPhotoSpec(
    val id: String,
    val name: String,
    val subtitle: String,
    val widthPx: Int,
    val heightPx: Int,
    val mmWidth: Int,
    val mmHeight: Int,
)

data class IdBgColor(
    val id: String,
    val name: String,
    val topColorArgb: Int,
    val bottomColorArgb: Int = topColorArgb,
)

object IdPhotoCatalog {
    val specs: List<IdPhotoSpec> = listOf(
        IdPhotoSpec("1inch", "一寸", "25×35mm · 295×413", 295, 413, 25, 35),
        IdPhotoSpec("small_1inch", "小一寸", "22×32mm · 260×378", 260, 378, 22, 32),
        IdPhotoSpec("large_1inch", "大一寸", "33×48mm · 390×567", 390, 567, 33, 48),
        IdPhotoSpec("2inch", "二寸", "35×49mm · 413×579", 413, 579, 35, 49),
        IdPhotoSpec("small_2inch", "小二寸", "35×45mm · 413×531", 413, 531, 35, 45),
        IdPhotoSpec("cet_kaoyan", "四六级/考研", "480×640px", 480, 640, 41, 54),
        IdPhotoSpec("teacher_resume", "教资/简历", "360×480px", 360, 480, 30, 41),
        IdPhotoSpec("us_visa", "美签/正方形", "51×51mm · 600×600", 600, 600, 51, 51),
    )

    val colors: List<IdBgColor> = listOf(
        IdBgColor("white", "纯白", 0xFFFFFFFF.toInt()),
        IdBgColor("blue", "证件蓝", 0xFF438EDB.toInt()),
        IdBgColor("deep_blue", "深蓝", 0xFF2B6CB0.toInt()),
        IdBgColor("red", "证件红", 0xFFD92B2B.toInt()),
        IdBgColor("blue_grad", "浅蓝渐变", 0xFF4F9BE8.toInt(), 0xFFD0E7FF.toInt()),
        IdBgColor("gray", "质感灰", 0xFFE6EAEF.toInt()),
    )

    fun customSpec(widthPx: Int, heightPx: Int): IdPhotoSpec {
        val w = widthPx.coerceIn(100, 2400)
        val h = heightPx.coerceIn(100, 2400)
        val mmW = (w * 25.4f / 300f).toInt().coerceAtLeast(10)
        val mmH = (h * 25.4f / 300f).toInt().coerceAtLeast(10)
        return IdPhotoSpec("custom_${w}x$h", "自定义", "${w}×${h}px", w, h, mmW, mmH)
    }
}
