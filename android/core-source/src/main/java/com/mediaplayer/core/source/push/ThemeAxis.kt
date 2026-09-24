package com.mediaplayer.core.source.push

/**
 * 主题轴的两个固定取值。
 *
 * 定义在共用层是刻意的：电视端（Compose/View 资源）、手机端（View 资源）、
 * 以及**局域网推源页**（一段服务端拼出来的 HTML）三处必须用同一组色值，
 * 否则手机扫码后看到的页面颜色会和电视端对不上。
 *
 * 之所以用字符串常量而不是 Android 的 `@color`：推源页由
 * [LocalPushServer] 在服务端渲染，那一步拿不到 Android 资源系统。
 */
object ThemeAxis {

    /** 标准轴 · 电光青。 */
    const val STANDARD_HEX = "#00F2FE"
    const val STANDARD_RGB = "0,242,254"

    /** 无痕轴 · 极光紫。 */
    const val INCOGNITO_HEX = "#A855F7"
    const val INCOGNITO_RGB = "168,85,247"

    fun hex(incognito: Boolean): String = if (incognito) INCOGNITO_HEX else STANDARD_HEX

    fun rgb(incognito: Boolean): String = if (incognito) INCOGNITO_RGB else STANDARD_RGB
}
