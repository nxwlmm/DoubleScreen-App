package com.mediaplayer.app.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.mediaplayer.app.R
import kotlin.math.roundToInt

/**
 * 主题轴渲染工具。
 *
 * ## 为什么这些 Drawable 用代码构建而不是 XML
 * V3.1 的换轴需求（标准=电光青 / 无痕=极光紫）会命中卡片描边、光晕、状态胶囊、
 * 主按钮等**十几个**元素。若走 XML，每个元素都要维护 `_standard` / `_incognito`
 * 两份 drawable，且换轴时必须逐个 `setBackgroundResource`——漏一个就是一处视觉不一致。
 * 集中在代码里构建后，换轴只需重刷一次可见 item。
 *
 * ## 光晕怎么做的
 * API 24 没有 `outlineSpotShadowColor`，真正的"外发光"做不了。
 * 这里用 `LayerDrawable` + 负 inset：底层画一圈比卡片大 3px 的半透明描边，
 * 视觉上等价于环境辉光。卡片所在的 item 根布局留了 3dp padding 来容纳它，
 * 否则会被父容器裁掉。
 */
object UiTheme {

    // ------------------------------------------------------------------ 轴色

    @ColorInt
    fun accent(context: Context, incognito: Boolean): Int =
        ContextCompat.getColor(
            context,
            if (incognito) R.color.accent_incognito else R.color.accent_standard
        )

    @ColorInt
    fun accentAlt(context: Context, incognito: Boolean): Int =
        ContextCompat.getColor(
            context,
            if (incognito) R.color.accent_incognito_alt else R.color.accent_standard_alt
        )

    @ColorInt
    fun onAccent(context: Context, incognito: Boolean): Int =
        ContextCompat.getColor(
            context,
            if (incognito) R.color.on_accent_incognito else R.color.on_accent_standard
        )

    // ------------------------------------------------------------------ 卡片

    /**
     * 卡片背景。
     *
     * @param focused 电视端传焦点状态；手机端恒传 false（触摸屏没有焦点概念）
     */
    fun cardBackground(context: Context, focused: Boolean, incognito: Boolean): Drawable {
        // GradientDrawable.cornerRadius 是 Float，而 dimension() 返回 Int —— 这里必须 toFloat()，
        // 否则报 "类型不匹配：推断类型为 Int，但预期为 Float"。半径提前转 Float 后，
        // 下面 `radius + glowWidth`（Float + Int = Float）也能直接用。
        val radius = dimension(context, R.dimen.card_radius).toFloat()
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(
                ContextCompat.getColor(
                    context,
                    if (focused) R.color.surface_card_focus else R.color.surface_card
                )
            )
            if (focused) {
                setStroke(
                    dimension(context, R.dimen.focus_stroke_width),
                    accent(context, incognito)
                )
            } else {
                setStroke(
                    dimension(context, R.dimen.stroke_width),
                    ColorUtils.setAlphaComponent(Color.WHITE, ALPHA_SUBTLE)
                )
            }
        }

        if (!focused) return body

        // 焦点态：额外套一圈 3px 的环境辉光（靠负 inset 向外扩）
        val glowWidth = dimension(context, R.dimen.focus_glow_width)
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius + glowWidth
            setColor(Color.TRANSPARENT)
            setStroke(glowWidth, ColorUtils.setAlphaComponent(accent(context, incognito), ALPHA_GLOW))
        }

        return LayerDrawable(arrayOf(glow, body)).apply {
            val outset = glowWidth
            setLayerInset(0, -outset, -outset, -outset, -outset)
            setLayerInset(1, 0, 0, 0, 0)
        }
    }

    // ------------------------------------------------------------------ 状态胶囊

    /** 胶囊语义。PING_* 与状态语义共用同一套形状，只是配色不同。 */
    enum class Tone { OK, FAIL, CHECK, DISABLED, PING_FAST, PING_GOOD, PING_SLOW, PING_TIMEOUT }

    fun pillBackground(context: Context, tone: Tone, incognito: Boolean): Drawable {
        val color = pillColor(context, tone, incognito)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dimension(context, R.dimen.pill_radius).toFloat()
            setColor(ColorUtils.setAlphaComponent(color, ALPHA_PILL_FILL))
            setStroke(dp(context, 1f), ColorUtils.setAlphaComponent(color, ALPHA_PILL_STROKE))
        }
    }

    @ColorInt
    fun pillTextColor(context: Context, tone: Tone, incognito: Boolean): Int =
        pillColor(context, tone, incognito)

    @ColorInt
    private fun pillColor(context: Context, tone: Tone, incognito: Boolean): Int = when (tone) {
        Tone.OK, Tone.PING_FAST -> ContextCompat.getColor(context, R.color.accent_mint)
        Tone.FAIL -> ContextCompat.getColor(context, R.color.accent_danger)
        Tone.CHECK -> accentAlt(context, incognito)
        // "良好"档跟随主题轴；"极速/较慢"恒定 —— 延迟语义不该被主题改掉含义
        Tone.PING_GOOD -> accent(context, incognito)
        Tone.PING_SLOW -> ContextCompat.getColor(context, R.color.accent_amber)
        Tone.DISABLED, Tone.PING_TIMEOUT -> ContextCompat.getColor(context, R.color.text_dim)
    }

    // ------------------------------------------------------------------ 按钮与其它

    /**
     * 胶囊开关背景（无痕开关、计数标签）。
     *
     * @param active true = 轴色高亮（如无痕已开启），false = 静默描边
     */
    fun chipBackground(context: Context, active: Boolean, incognito: Boolean): Drawable =
        if (active) {
            pillBackground(context, Tone.PING_GOOD, incognito)
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dimension(context, R.dimen.pill_radius).toFloat()
                setColor(ColorUtils.setAlphaComponent(Color.WHITE, ALPHA_CHIP_IDLE_FILL))
                setStroke(dp(context, 1f), ColorUtils.setAlphaComponent(Color.WHITE, ALPHA_SUBTLE))
            }
        }

    /** 服务地址框：轴色描边的圆角矩形（投源面板用）。 */
    fun urlBoxBackground(context: Context, incognito: Boolean): Drawable {
        val accent = accent(context, incognito)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 13f).toFloat()
            setColor(ColorUtils.setAlphaComponent(accent, ALPHA_PILL_FILL))
            setStroke(dp(context, 1f), ColorUtils.setAlphaComponent(accent, ALPHA_PILL_STROKE))
        }
    }

    /** 主按钮：轴色渐变。 */
    fun primaryButtonBackground(context: Context, incognito: Boolean): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(accent(context, incognito), accentAlt(context, incognito))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 14f).toFloat()
        }

    /** 幽灵按钮：透明底 + 细描边。 */
    fun ghostButtonBackground(context: Context): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 14f).toFloat()
            setColor(ColorUtils.setAlphaComponent(Color.WHITE, ALPHA_GHOST_FILL))
            setStroke(
                dp(context, 1f),
                ColorUtils.setAlphaComponent(Color.WHITE, ALPHA_SUBTLE)
            )
        }

    /** 危险按钮：红字红边。 */
    fun dangerButtonBackground(context: Context): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 14f).toFloat()
            val danger = ContextCompat.getColor(context, R.color.accent_danger)
            setColor(ColorUtils.setAlphaComponent(danger, ALPHA_PILL_FILL))
            setStroke(dp(context, 1f), ColorUtils.setAlphaComponent(danger, ALPHA_DANGER_STROKE))
        }

    /** 资源类型图标的圆角底：轴色 / 紫 / 薄荷三选一。 */
    fun tileBackground(context: Context, tileColorRes: Int, incognito: Boolean): Drawable {
        val base = tileColor(context, tileColorRes, incognito)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 14f).toFloat()
            setColor(ColorUtils.setAlphaComponent(base, ALPHA_TILE_FILL))
            setStroke(dp(context, 1f), ColorUtils.setAlphaComponent(base, ALPHA_PILL_STROKE))
        }
    }

    /**
     * 类型图标的着色。
     *
     * 单仓用轴色（会随主题变化），多仓/直播用固定的紫与薄荷 ——
     * 这三种颜色构成一套稳定的"类型语义"，不该被无痕模式打乱。
     */
    @ColorInt
    fun tileColor(context: Context, tileColorRes: Int, incognito: Boolean): Int =
        if (tileColorRes == R.color.accent_standard) accent(context, incognito)
        else ContextCompat.getColor(context, tileColorRes)

    // ------------------------------------------------------------------ 工具

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun dimension(context: Context, resId: Int): Int =
        context.resources.getDimensionPixelSize(resId).coerceAtLeast(1)

    // 与 V3.1 原型对齐的透明度阶梯
    private const val ALPHA_SUBTLE = 0x14          // 8%  白细线
    private const val ALPHA_GLOW = 0x18            // 9%  焦点环境辉光
    private const val ALPHA_PILL_FILL = 0x1A       // 10% 胶囊填充
    private const val ALPHA_PILL_STROKE = 0x47     // 28% 胶囊描边
    private const val ALPHA_GHOST_FILL = 0x0D      // 5%  幽灵按钮
    private const val ALPHA_DANGER_STROKE = 0x66   // 40% 危险按钮描边
    private const val ALPHA_TILE_FILL = 0x1A       // 10% 图标底
    private const val ALPHA_CHIP_IDLE_FILL = 0x0A  // 4%  未激活胶囊填充
}
