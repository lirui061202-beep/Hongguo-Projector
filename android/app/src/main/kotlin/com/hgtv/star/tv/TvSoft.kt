// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 新拟态的底板形态。
 *
 * 新拟态的全部层次都由「同一块底色的凸起 / 凹陷」表达，因此形状只有四种：
 * 凸起（[RAISED]）、凹陷（[INSET]）、只有一圈描边（[RING]）、压在图片上的内暗角（[SHADE]）。
 * 任何控件的外观都必须从这里挑一种，不再自己拼 GradientDrawable 的描边。
 */
internal enum class SoftStyle { RAISED, INSET, RING, SHADE }

/**
 * 柔光（新拟态）绘制核心。
 *
 * ## 为什么需要柔光带
 *
 * 新拟态的「凸起」= 右下一条深投影 + 左上一条浅高光；「凹陷」= 底板压暗 + 内阴影。
 * Android 的 `GradientDrawable` 没有模糊，`setShadowLayer` 又只在软件画布上生效
 * （直接用在硬件加速的 View 背景上不画阴影），`RenderEffect` 要 API 31 且每次绘制都要重新模糊。
 * 因此这里把柔光**预渲染成一张位图**，控件每帧只是画一张已经模糊好的图：
 *
 * - 位图按控件尺寸做 LRU 缓存，同一屏里尺寸相同的卡片（一架子海报、一排按钮）共用一张；
 * - 位图按 [SCALE] 半分辨率渲染再放大：投影本来就是模糊的，放大看不出来，显存和生成时间各省 4 倍；
 * - 低内存设备把 [quality] 调小：投影更紧、更淡，生成也更快，层次不丢。
 *
 * ## 为什么柔光画在控件**内部**的留白里
 *
 * 投影如果画到控件边界之外，就要求父级不裁剪；而滚动容器的裁剪规则（见 `tvScrollHost`）
 * 恰恰必须打开。两者会互相打架：要么描边被切掉，要么卡片盖住页头。
 * 因此每个控件四周留出 [TvStyle.SOFT_BAND] 的透明带（就是它自己的 padding），
 * 底板画在这条带里面。投影、高光、焦点环全部落在控件自己的边界内，
 * 滚动容器的裁剪与焦点余量都只负责「放大 1.05 倍溢出的 2.5%」这一件事。
 */
internal object Soft {
    /** 低内存设备在 onCreate 里调小；只影响柔光的紧致度，不影响层次数量。 */
    var quality = 1f

    /** 柔光位图相对控件尺寸的缩放。 */
    const val SCALE = .5f

    /** 缓存条数上限（次要约束，真正的上限是下面的字节预算）。 */
    private const val MAX_ENTRIES = 48

    /**
     * 缓存总字节预算。柔光是**可重建的纯缓存**，宁可多重建几次也不能把低端电视的内存吃掉：
     * 一张 4K 画布上的弹窗柔光位图约 2 MiB，8 MiB 够放三四个尺寸同时可见的面板。
     */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 单张柔光位图的上限：超了就不画投影（宁可少一层，也不要为一个大面板分配巨图）。 */
    private const val MAX_BITMAP_BYTES = 3 * 1024 * 1024

    /** 一张控件的柔光图层：凸起是位图，凹陷 / 暗角是渐变。 */
    class Layers(val shadow: Bitmap?, val dark: Shader?, val light: Shader?) {
        val bytes: Int = (shadow?.allocationByteCount ?: 0) + if (dark != null || light != null) 512 else 0
    }

    private val cache = LinkedHashMap<String, Layers>(32, .75f, true)
    private var bytes = 0

    fun layers(key: String, build: () -> Layers): Layers {
        cache[key]?.let { return it }
        val made = build()
        cache[key] = made
        bytes += made.bytes
        // 按访问顺序淘汰最旧的一条，直到回到预算内；至少留一条，免得同一帧里反复重建。
        val oldest = cache.entries.iterator()
        while (bytes > MAX_BYTES && cache.size > 1 && oldest.hasNext()) {
            val entry = oldest.next()
            bytes -= entry.value.bytes
            oldest.remove()
        }
        if (cache.size > MAX_ENTRIES) {
            val extra = cache.entries.iterator()
            while (cache.size > MAX_ENTRIES && extra.hasNext()) {
                val entry = extra.next()
                bytes -= entry.value.bytes
                extra.remove()
            }
        }
        return made
    }

    fun maxBitmapBytes() = MAX_BITMAP_BYTES

    /** 低内存 / 退到后台时释放柔光缓存；下次绘制会按需重建。 */
    fun trim() {
        cache.clear()
        bytes = 0
    }
}

/**
 * 一块新拟态底板。用法：`view.background = TvStyle.soft(context, ...)`。
 *
 * 底板几何：`bounds` 四边各内缩 [bandPx]，剩下的圆角矩形就是「面板」。
 * 因此控件的 padding 必须与 [bandPx] 一致（见 `paintButton` / 各组件），
 * 否则面板会盖住内容或内容压到投影带里。
 *
 * 三种绘制路径：
 * - [SoftStyle.RAISED]：先画预渲染的柔光位图（右下深投影 + 左上高光 + 可选外发光），再画面板；
 * - [SoftStyle.INSET]：先画面板，再裁剪到面板内画两条方向相反的内阴影渐变，得到「压进去」的槽；
 * - [SoftStyle.RING]：只画面板（通常透明）与一圈描边，用作焦点环；
 * - [SoftStyle.SHADE]：面板 + 径向暗角，压在图片上做「嵌进底板的井」。
 */
internal class SoftDrawable(
    private val fill: Int,
    private val radiusPx: Float,
    private val bandPx: Float,
    private val style: SoftStyle,
    private val ringColor: Int = Color.TRANSPARENT,
    private val ringWidthPx: Float = 0f,
    private val glowColor: Int = Color.TRANSPARENT,
    private val depth: Float = 1f
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plate = RectF()
    private val dst = RectF()
    private val clip = Path()
    private var key: String? = null
    private var cached: Soft.Layers? = null

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        // 透明度由 fill / ring 颜色自带 alpha 决定；这里同步 paint 的 alpha 供外部整体淡出用。
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
    }

    override fun onBoundsChange(bounds: Rect) {
        plate.set(
            bounds.left + bandPx,
            bounds.top + bandPx,
            bounds.right - bandPx,
            bounds.bottom - bandPx
        )
        dst.set(bounds)
        key = null
        cached = null
    }

    override fun draw(canvas: Canvas) {
        val width = plate.width()
        val height = plate.height()
        if (width <= 0f || height <= 0f) return
        val radius = radiusPx.coerceAtMost(minOf(width, height) * .5f)
        when (style) {
            SoftStyle.RAISED -> {
                layers().shadow?.let { canvas.drawBitmap(it, null, dst, bitmapPaint) }
                fill(canvas, radius)
            }
            SoftStyle.INSET -> {
                fill(canvas, radius)
                inner(canvas, radius)
            }
            SoftStyle.SHADE -> {
                fill(canvas, radius)
                shade(canvas, radius)
            }
            SoftStyle.RING -> fill(canvas, radius)
        }
        if (ringWidthPx > 0f && Color.alpha(ringColor) > 0) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringWidthPx
            paint.color = ringColor
            canvas.drawRoundRect(plate, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun fill(canvas: Canvas, radius: Float) {
        if (Color.alpha(fill) == 0) return
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(plate, radius, radius, paint)
    }

    /** 凹陷：裁剪到面板内，左上一条深色内阴影、右下一条浅色内阴影。 */
    private fun inner(canvas: Canvas, radius: Float) {
        val layer = layers()
        val dark = layer.dark ?: return
        val save = canvas.save()
        clip.reset()
        clip.addRoundRect(plate, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = dark
        canvas.drawRect(plate, paint)
        layer.light?.let {
            paint.shader = it
            canvas.drawRect(plate, paint)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }

    /** 暗角：压在图片上的井，四条边往里暗一圈。 */
    private fun shade(canvas: Canvas, radius: Float) {
        val layer = layers()
        val vignette = layer.light ?: return
        val save = canvas.save()
        clip.reset()
        clip.addRoundRect(plate, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = vignette
        canvas.drawRect(plate, paint)
        paint.shader = null
        canvas.restoreToCount(save)
    }

    private fun layers(): Soft.Layers {
        cached?.let { return it }
        val id = key ?: buildString {
            append(bounds.left).append('+').append(bounds.top).append(':')
            append(bounds.width()).append('x').append(bounds.height())
            append("r").append(radiusPx.toInt())
            append("b").append(bandPx.toInt())
            append("s").append(style.ordinal)
            append("g").append(glowColor)
            append("d").append((depth * 10).toInt())
            append("q").append((Soft.quality * 10).toInt())
            // 主题：柔光位图里烤着当时的投影 / 高光色，换主题必须换 key，
            // 否则会拿浅色材质的位图画在深色底上（TvStyle.applyTheme 还会整体 trim 一次）。
            append("t").append(TvStyle.themeId)
        }.also { key = it }
        val made = Soft.layers(id) { build() }
        cached = made
        return made
    }

    private fun build(): Soft.Layers = when (style) {
        SoftStyle.INSET -> Soft.Layers(null, innerGradient(false), innerGradient(true))
        SoftStyle.SHADE -> Soft.Layers(null, null, vignette())
        // 完全透明、又没有外发光的底板（文字按钮的常态）不画任何东西：
        // 免得为一块看不见的板子白生成一张柔光位图。
        else -> if (Color.alpha(fill) == 0 && Color.alpha(glowColor) == 0) Soft.Layers(null, null, null)
        else Soft.Layers(shadowBitmap(), null, null)
    }

    /** [light] = true 时返回右下那条浅色内阴影，否则是左上那条深色内阴影。 */
    private fun innerGradient(light: Boolean): LinearGradient {
        val reach = bandPx * 2.6f * depth
        return if (light) LinearGradient(
            plate.right, plate.bottom, plate.right - reach, plate.bottom - reach,
            TvStyle.innerLight, Color.TRANSPARENT, Shader.TileMode.CLAMP
        ) else LinearGradient(
            plate.left, plate.top, plate.left + reach, plate.top + reach,
            TvStyle.innerDark, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    private fun vignette(): RadialGradient {
        val cx = plate.centerX()
        val cy = plate.centerY()
        val radius = maxOf(plate.width(), plate.height()) * .62f
        return RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, TvStyle.innerDark),
            floatArrayOf(.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** 预渲染的柔光位图：外发光（可选）+ 右下深投影 + 左上浅高光，半分辨率。 */
    private fun shadowBitmap(): Bitmap? {
        val scale = Soft.SCALE
        val width = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val height = (bounds.height() * scale).toInt().coerceAtLeast(1)
        if (width.toLong() * height * 4 > Soft.maxBitmapBytes()) return null
        val quality = Soft.quality
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        val band = bandPx * scale
        val rect = RectF(band, band, width - band, height - band)
        val radius = radiusPx * scale
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        if (Color.alpha(glowColor) > 0) {
            brush.color = glowColor
            brush.setShadowLayer(band * 1.35f * quality, 0f, 0f, glowColor)
            canvas.drawRoundRect(rect, radius, radius, brush)
        }
        brush.setShadowLayer(
            band * 1.05f * quality,
            band * .36f * depth * quality,
            band * .46f * depth * quality,
            TvStyle.shadowDark
        )
        brush.color = TvStyle.shadowDark
        canvas.drawRoundRect(rect, radius, radius, brush)
        brush.setShadowLayer(
            band * .95f * quality,
            -band * .32f * depth * quality,
            -band * .40f * depth * quality,
            TvStyle.shadowLight
        )
        brush.color = TvStyle.shadowLight
        canvas.drawRoundRect(rect, radius, radius, brush)
        return out
    }
}
