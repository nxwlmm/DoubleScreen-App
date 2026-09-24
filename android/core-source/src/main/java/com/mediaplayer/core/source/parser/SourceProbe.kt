package com.mediaplayer.core.source.parser

import android.util.JsonReader
import android.util.JsonToken
import com.mediaplayer.core.source.model.FailReason
import com.mediaplayer.core.source.model.ParseResult
import com.mediaplayer.core.source.model.SourceKind
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * 判定"一个源是否可用"的策略。
 *
 * 抽成接口是因为不同场景的容忍度不同：
 * - 用户手动添加、自动故障转移：用 [StrictValidator]，避免把一个半成品源设为当前。
 * - 批量导入 / 探活巡检：用 [LenientValidator]，只要结构基本成立就收，避免误杀。
 */
fun interface SourceValidator {
    /** 返回 null 表示通过，否则返回不可用原因。 */
    fun validate(result: ParseResult): FailReason?
}

/**
 * 严格策略：对齐安全契约——单仓必须同时具备 `sites`（非空）与 `spider`。
 *
 * 注意这里会拦掉一类"纯直播单仓"（只有 lives 没有 spider），如果项目后续要支持，
 * 请改用 [LenientValidator] 或在数据模型里显式声明该源为 LIVE 类型。
 */
object StrictValidator : SourceValidator {
    override fun validate(result: ParseResult): FailReason? = when (result) {
        is ParseResult.Invalid -> result.reason
        is ParseResult.Ok -> when (result.kind) {
            SourceKind.SINGLE -> when {
                result.itemCount <= 0 -> FailReason.Schema("sites 为空或不存在")
                result.spider.isNullOrBlank() -> FailReason.Schema("缺少 spider 字段")
                else -> null
            }
            SourceKind.MULTI ->
                if (result.childUrls.isEmpty()) FailReason.Schema("urls / storeHouse 为空") else null
            SourceKind.LIVE ->
                if (result.itemCount <= 0) FailReason.Schema("未解析到任何频道") else null
            SourceKind.UNKNOWN -> FailReason.Schema("无法识别的源类型")
        }
    }
}

/**
 * 宽松策略：单仓只要求 `sites` 非空，`spider` 缺失仅视为可接受的降级。
 * 用于导入历史配置、或对接不带 Spider 的纯点播 JSON。
 */
object LenientValidator : SourceValidator {
    override fun validate(result: ParseResult): FailReason? = when (result) {
        is ParseResult.Invalid -> result.reason
        is ParseResult.Ok -> when (result.kind) {
            SourceKind.SINGLE ->
                if (result.itemCount <= 0) FailReason.Schema("sites 为空或不存在") else null
            SourceKind.MULTI ->
                if (result.childUrls.isEmpty()) FailReason.Schema("urls / storeHouse 为空") else null
            SourceKind.LIVE ->
                if (result.itemCount <= 0) FailReason.Schema("未解析到任何频道") else null
            SourceKind.UNKNOWN -> FailReason.Schema("无法识别的源类型")
        }
    }
}

/**
 * 源内容探测器：把一段字节流判定成 [ParseResult]。
 *
 * ## 为什么不用 Gson / kotlinx.serialization 反序列化
 * 多仓索引动辄 1~4MB，全量构建对象树会在低配盒子上产生大量临时对象，
 * 是 OOM 与 GC 卡顿的常见来源。这里统一走**流式扫描**：只数元素个数、
 * 只取必要的几个字段，其余一律 `skipValue()` 快速跳过，峰值内存与源体积无关。
 *
 * ## 格式识别
 * 不盲信调用方声明的类型，而是嗅探首个非空白字符：
 * `{` → JSON（再按内容区分单仓/多仓）、`#` → M3U、其余按声明类型兜底。
 */
object SourceProbe {

    /** 单仓 / 多仓 JSON 的体积上限。 */
    const val MAX_JSON_BYTES = 4L * 1024 * 1024

    /** 直播表的体积上限（M3U/TXT 可达数 MB，但不能无限）。 */
    const val MAX_LIVE_BYTES = 2L * 1024 * 1024

    /** 直播表最大行数，防止畸形文件把 CPU 占满。 */
    const val MAX_LIVE_LINES = 30_000

    /** 多仓最多取前 N 个子仓地址——UI 不会展示更多，没必要全读。 */
    const val MAX_CHILD_URLS = 64

    /** 数组元素计数上限：超出后仍会完整 skip，但不再累加，防止计数器溢出与无谓开销。 */
    private const val COUNT_LIMIT = 100_000

    /**
     * @param declaredKind 调用方声明的类型（用户在 UI 上选的），仅作兜底参考
     * @param contentType  HTTP `Content-Type`，用于识别 UTF-8/GBK 等编码
     * @param stream       响应体流。**本方法会消费并关闭该流**（包括异常路径）
     */
    fun parse(
        declaredKind: SourceKind,
        url: String,
        contentType: String?,
        stream: InputStream,
        contentLength: Long = -1L
    ): ParseResult {
        val charset = charsetOf(contentType)
        val hardLimit = if (declaredKind == SourceKind.LIVE) MAX_LIVE_BYTES else MAX_JSON_BYTES

        // 第一道闸：声明的长度就已超标，直接拒绝，一个字节都不读
        if (contentLength > hardLimit) return ParseResult.Invalid(FailReason.TooLarge(contentLength))

        val limited = LimitedInputStream(stream, hardLimit)
        return try {
            BufferedReader(InputStreamReader(limited, charset), BUFFER_CHARS).use { reader ->
                val first = peekFirstMeaningfulChar(reader)
                    ?: return ParseResult.Invalid(FailReason.Empty("响应体为空"))

                when {
                    first == '{' || first == '[' -> scanJson(reader)
                    first == '#' -> scanLive(reader)
                    // 首字符既不是 JSON 也不是 M3U 头：用「声明类型 + URL 后缀」共同判断。
                    // .txt 直播表常常没有 #EXTM3U 头，只靠声明类型容易漏判成 JSON。
                    declaredKind == SourceKind.LIVE ||
                        url.endsWith(".m3u", ignoreCase = true) ||
                        url.endsWith(".m3u8", ignoreCase = true) ||
                        url.endsWith(".txt", ignoreCase = true) -> scanLive(reader)
                    else -> scanJson(reader)
                }
            }
        } catch (e: SizeLimitExceededException) {
            ParseResult.Invalid(FailReason.TooLarge(e.bytes))
        } catch (e: Exception) {
            // 畸形 JSON / 编码异常统一归为结构不符，交由上层决定是否切换下一源
            ParseResult.Invalid(FailReason.Schema(e.javaClass.simpleName + ": " + (e.message ?: "解析失败")))
        } finally {
            runCatching { stream.close() }
        }
    }

    // ---------------------------------------------------------------- JSON

    /**
     * 单仓 / 多仓统一扫描。
     *
     * 扫描的真实字段（对齐 FongMi catvod 契约）：
     * - `sites`      Array  单仓的站点列表
     * - `spider`     String Spider 实现入口（jar/py/js 地址）
     * - `lives`      Array  内嵌直播频道
     * - `urls`       Array  多仓写法之一：字符串数组
     * - `storeHouse` Array  多仓写法之二：对象数组，子项含 sourceUrl / sourceName
     */
    private fun scanJson(reader: BufferedReader): ParseResult {
        var siteCount = 0
        var liveCount = 0
        var spider: String? = null
        var childUrls: List<String> = emptyList()
        var declaredName: String? = null

        JsonReader(reader).use { jr ->
            // 少数源在 JSON 外层包了 BOM 或注释行，JsonReader 会自行跳过空白
            if (jr.peek() != JsonToken.BEGIN_OBJECT) {
                return ParseResult.Invalid(FailReason.Schema("JSON 根节点不是对象"))
            }
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "sites" -> siteCount = countArrayElements(jr)
                    "lives" -> liveCount = countArrayElements(jr)

                    "spider" -> {
                        if (jr.peek() == JsonToken.STRING) spider = jr.nextString() else jr.skipValue()
                    }

                    "urls" -> childUrls = readStringArray(jr, MAX_CHILD_URLS)

                    "storeHouse" -> {
                        val parsed = readStoreHouse(jr, MAX_CHILD_URLS)
                        childUrls = parsed.first
                        if (parsed.second != null) declaredName = parsed.second
                    }

                    // 仓名可能出现在不同层级，多取几个常见 key
                    "name", "sourceName" -> {
                        if (jr.peek() == JsonToken.STRING) {
                            if (declaredName == null) declaredName = jr.nextString()
                        } else {
                            jr.skipValue()
                        }
                    }

                    else -> jr.skipValue()
                }
            }
            jr.endObject()
        }

        // 判定类型：出现子仓地址即视为多仓，否则按单仓处理（含纯 lives 的单仓）
        val kind = if (childUrls.isNotEmpty()) SourceKind.MULTI else SourceKind.SINGLE
        val itemCount = if (kind == SourceKind.MULTI) childUrls.size else siteCount + liveCount

        return ParseResult.Ok(
            kind = kind,
            itemCount = itemCount,
            spider = spider,
            childUrls = childUrls,
            declaredName = declaredName
        )
    }

    /** 只数元素个数，内容全部快速跳过。返回 0 表示不是数组或为空。 */
    private fun countArrayElements(jr: JsonReader): Int {
        if (jr.peek() != JsonToken.BEGIN_ARRAY) {
            jr.skipValue()
            return 0
        }
        var count = 0
        jr.beginArray()
        while (jr.hasNext()) {
            jr.skipValue()
            if (count < COUNT_LIMIT) count++
        }
        jr.endArray()
        return count
    }

    /** 读字符串数组，最多取前 [limit] 个（其余仍完整跳过，保证流位置正确）。 */
    private fun readStringArray(jr: JsonReader, limit: Int): List<String> {
        if (jr.peek() != JsonToken.BEGIN_ARRAY) {
            jr.skipValue()
            return emptyList()
        }
        val out = ArrayList<String>(minOf(limit, 16))
        jr.beginArray()
        while (jr.hasNext()) {
            if (jr.peek() == JsonToken.STRING) {
                val value = jr.nextString()
                if (out.size < limit && value.isNotBlank()) out.add(value.trim())
            } else {
                jr.skipValue()
            }
        }
        jr.endArray()
        return out
    }

    /**
     * 解析 FongMi 风格的多仓索引：
     * ```json
     * { "storeHouse": [ { "sourceName": "仓A", "sourceUrl": "https://..." }, ... ] }
     * ```
     * 兼容两种降级写法：数组里直接放字符串、或子项用 `url` / `name` 作为 key。
     *
     * @return Pair(子仓地址列表, 第一个仓的展示名)
     */
    private fun readStoreHouse(jr: JsonReader, limit: Int): Pair<List<String>, String?> {
        if (jr.peek() != JsonToken.BEGIN_ARRAY) {
            jr.skipValue()
            // 必须显式写 <String>：`emptyList() to null` 里的 to 是泛型中缀函数，
            // Kotlin 不会把外层声明的 Pair<List<String>, String?> 反向传播进 emptyList()，
            // 于是报 "信息不足以推断类型变量 T"
            return emptyList<String>() to null
        }
        val urls = ArrayList<String>(minOf(limit, 8))
        var firstName: String? = null

        jr.beginArray()
        while (jr.hasNext()) {
            when (jr.peek()) {
                JsonToken.STRING -> {
                    val direct = jr.nextString().trim()
                    if (direct.startsWith("http") && urls.size < limit) urls.add(direct)
                }
                JsonToken.BEGIN_OBJECT -> {
                    var childUrl: String? = null
                    var childName: String? = null
                    jr.beginObject()
                    while (jr.hasNext()) {
                        when (jr.nextName()) {
                            "sourceUrl", "url", "api" ->
                                if (jr.peek() == JsonToken.STRING) childUrl = jr.nextString().trim() else jr.skipValue()
                            "sourceName", "name" ->
                                if (jr.peek() == JsonToken.STRING) childName = jr.nextString().trim() else jr.skipValue()
                            else -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                    if (!childUrl.isNullOrBlank() && childUrl.startsWith("http")) {
                        if (urls.size < limit) urls.add(childUrl)
                        if (firstName == null) firstName = childName
                    }
                }
                else -> jr.skipValue()
            }
        }
        jr.endArray()
        return urls to firstName
    }

    // ---------------------------------------------------------------- 直播

    /**
     * 扫描 M3U / TXT 直播表。
     *
     * M3U：以 `#EXTM3U` 开头，`#EXTINF:` 行代表一个频道。
     * TXT：常见的 `分组,#genre#` + `频道名,http://...` 两栏格式。
     *
     * 只统计频道数与是否存在可用 http 地址，不把整张表读进内存。
     */
    private fun scanLive(reader: BufferedReader): ParseResult {
        var channelCount = 0
        var hasHttpUrl = false
        var lines = 0
        var sawExtM3u = false

        while (true) {
            val line = reader.readLine() ?: break
            if (++lines > MAX_LIVE_LINES) break

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> sawExtM3u = true

                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    // M3U 频道描述行，真正的地址在下一行；这里只负责计数
                    channelCount++
                }

                trimmed.startsWith("#") -> Unit // 其余 # 开头为分组/注释（如 #genre#），忽略

                else -> {
                    // 裸地址行（M3U 的 url 行）或 TXT 的 "名称,地址" 行
                    val candidate = trimmed.substringAfterLast(',')
                    if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                        hasHttpUrl = true
                        if (!sawExtM3u) channelCount++ // TXT 格式：一行即一个频道
                    }
                }
            }
        }

        return when {
            lines == 0 -> ParseResult.Invalid(FailReason.Empty("直播表为空"))
            channelCount == 0 -> ParseResult.Invalid(FailReason.Schema("未找到任何频道行"))
            !hasHttpUrl -> ParseResult.Invalid(FailReason.Schema("未找到 http(s) 播放地址"))
            // 直播源没有 Spider 实现，spider 显式传 null ——
            // ParseResult.Ok 的 spider 参数没有默认值，漏传即编译失败
            else -> ParseResult.Ok(kind = SourceKind.LIVE, itemCount = channelCount, spider = null)
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 读取首个有意义的字符（跳过空白与 BOM），并把流位置复位。
     *
     * ⚠️ `mark` 的 readAheadLimit 必须给足：它的语义是"最多可回退这么多字符"，
     * 若给 2 而实际跳过了 3 个空白符，`reset()` 会抛 IOException。
     * 这里直接给到缓冲区量级，任何正常头部都能覆盖。
     */
    private fun peekFirstMeaningfulChar(reader: BufferedReader): Char? {
        reader.mark(MARK_LIMIT)
        var c = reader.read()
        while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == BOM) {
            c = reader.read()
            if (c == -1) break
        }
        reader.reset()
        return if (c == -1) null else c.toChar()
    }

    /** 从 Content-Type 提取编码；多数此类配置源是 UTF-8，少数 TXT 频道表用 GBK。 */
    private fun charsetOf(contentType: String?): Charset {
        val ct = contentType?.lowercase() ?: return Charsets.UTF_8
        return when {
            ct.contains("gbk") || ct.contains("gb2312") || ct.contains("gb18030") ->
                runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)
            ct.contains("charset=utf-8") || ct.contains("utf8") -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
    }

    private const val BUFFER_CHARS = 8 * 1024

    /** mark/reset 的回退上限。取到缓冲区量级，保证"跳过任意正常长度的头部"后仍可复位。 */
    private const val MARK_LIMIT = 1024
    private const val BOM = 0xFEFF
}

/** 读取字节数超过上限时抛出，由 [SourceProbe.parse] 翻译成 [FailReason.TooLarge]。 */
internal class SizeLimitExceededException(val bytes: Long) : RuntimeException("content exceeds $bytes bytes")

/**
 * 带字节上限的输入流包装。
 *
 * 这是低内存约束的第一道防线：即便服务端没有给出 Content-Length（chunked），
 * 也能在读取过程中及时掐断，而不是等到 OOM 才崩。
 */
internal class LimitedInputStream(
    private val delegate: InputStream,
    private val limit: Long
) : InputStream() {

    private var consumed = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) checkLimit(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        // checkLimit 的形参是 Int，这里不能传 n.toLong()
        if (n > 0) checkLimit(n)
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()

    private fun checkLimit(delta: Int) {
        consumed += delta
        if (consumed > limit) throw SizeLimitExceededException(consumed)
    }
}
