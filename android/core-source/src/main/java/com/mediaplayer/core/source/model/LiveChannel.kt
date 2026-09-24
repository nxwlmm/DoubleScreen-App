package com.mediaplayer.core.source.model

/**
 * 直播频道。
 *
 * [group] 来自两种格式里的分组信息：
 * - TXT：`分组名,#genre#` 之后的行都属于该分组
 * - M3U：`#EXTINF` 属性里的 `group-title="分组名"`
 *
 * 解析不了的行会被跳过而不是报错 —— 直播源里混着广告行、空行、乱码是常态。
 */
data class LiveChannel(
    val name: String,
    val url: String,
    val group: String
)
