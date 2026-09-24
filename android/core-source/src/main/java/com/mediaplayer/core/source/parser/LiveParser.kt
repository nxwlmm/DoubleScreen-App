package com.mediaplayer.core.source.parser

import com.mediaplayer.core.source.model.LiveChannel

/**
 * 直播源解析器：支持 **M3U** 与 **TXT** 两种社区通用格式。
 *
 * ```
 * M3U：
 *   #EXTM3U
 *   #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1
 *   http://live.example.com/cctv1.m3u8
 *
 * TXT：
 *   央视,#genre#
 *   CCTV-1,http://live.example.com/cctv1.m3u8
 * ```
 *
 * ## 容错策略
 * 直播源里混着广告行、占位行、乱码是常态，因此：
 * - URL **必须**以 `http://` 或 `https://` 开头，否则整行丢弃；
 * - 频道名为空时用 URL 末段兜底；
 * - 分组为空时归入「未分组」；
 * - 任何单行解析失败都只丢那一行，绝不让整个文件报废。
 *
 * ## 为什么不用正则一刀切
 * M3U 的 `#EXTINF` 属性顺序不固定（`tvg-logo` 可能出现在 `group-title` 前后），
 * 用 `Regex("""group-title="([^"]*)"""")` 定位比按位置切更抗变化。
 */
object LiveParser {

    private const val DEFAULT_GROUP = "未分组"

    // 用普通字符串 + 转义，而不用 raw string（"""..."）——
    // raw string 末尾会出现 4 连引号（正则闭合 + 字符串结束），Kotlin 合法
    // 但可读性差，且词法检查工具在这种边界上容易误判
    private val GROUP_TITLE = Regex("group-title=\"([^\"]*)\"")

    /** 解析入口：自动识别格式。 */
    fun parse(text: String): List<LiveChannel> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()

        // 判定依据：M3U 必有 #EXTM3U 或 #EXTINF 头；TXT 的特征是 "#genre#" 分组行
        val isM3U = lines.any { it.startsWith("#EXTM3U") || it.startsWith("#EXTINF") }
        return if (isM3U) parseM3U(lines) else parseTxt(lines)
    }

    // ------------------------------------------------------------------ M3U

    private fun parseM3U(lines: List<String>): List<LiveChannel> {
        val out = ArrayList<LiveChannel>(256)
        var pendingName: String? = null
        var pendingGroup: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF") -> {
                    pendingGroup = GROUP_TITLE.find(line)?.groupValues?.takeIf { it.isNotBlank() }
                    // 频道名 = 最后一个逗号之后的部分（属性里可能含逗号，所以取 lastIndexOf）
                    val comma = line.lastIndexOf(',')
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else null
                }

                line.startsWith("#") -> Unit // 其他指令行（#EXTVLCOPT 等）忽略

                else -> {
                    if (isPlayable(line)) {
                        out += LiveChannel(
                            name = pendingName?.takeIf { it.isNotEmpty() } ?: urlFallback(line),
                            url = line,
                            group = pendingGroup?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP
                        )
                    }
                    pendingName = null
                    pendingGroup = null
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ TXT

    private fun parseTxt(lines: List<String>): List<LiveChannel> {
        val out = ArrayList<LiveChannel>(256)
        var group = DEFAULT_GROUP

        for (line in lines) {
            // 分组声明行：`央视,#genre#`
            if (line.endsWith(",#genre#") || line.endsWith("，#genre#")) {
                group = line.substringBefore(',').substringBefore('，').trim()
                    .takeIf { it.isNotEmpty() } ?: DEFAULT_GROUP
                continue
            }

            val comma = line.indexOf(',')
            if (comma <= 0) continue
            val name = line.substring(0, comma).trim()
            val url = line.substring(comma + 1).trim()

            // TXT 里 url 字段可能是 `#genre#`（分组的另一种写法），跳过
            if (!isPlayable(url)) continue

            out += LiveChannel(
                name = name.ifBlank { urlFallback(url) },
                url = url,
                group = group
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 工具

    private fun isPlayable(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun urlFallback(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { url.take(24) }
}
