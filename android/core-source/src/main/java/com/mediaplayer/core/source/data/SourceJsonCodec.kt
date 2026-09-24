package com.mediaplayer.core.source.data

import com.mediaplayer.core.source.model.SourceEntry
import com.mediaplayer.core.source.model.SourceKind

/**
 * 源配置的序列化。
 *
 * ## 为什么手写而不用 `org.json` / Gson / kotlinx.serialization
 * 1. **测试可用性**：`org.json` 在 Android 单元测试里是 not-mocked 的空壳，
 *    一调用就抛异常。要让它可用得在 `testImplementation` 里额外引一份 JVM 版，
 *    这就破坏了"纯 JVM 可执行"的前提。
 * 2. **结构固定**：要序列化的就是一个长度 ≤50 的数组，每条 6 个字段。
 *    通用序列化库带来的反射/注解开销与 APK 增量都不划算。
 * 3. **可预测性**：手写编码可以直接预估输出大小、一次分配 StringBuilder，
 *    在低内存设备上比"构建中间 Map 再序列化"少一轮对象分配。
 *
 * ## 格式
 * ```json
 * {
 *   "version": 1,
 *   "updatedAt": 1700000000000,
 *   "activeId": "uuid-or-null",
 *   "entries": [
 *     { "id":"...", "name":"...", "url":"...", "kind":"LIVE", "priority":0, "enabled":true }
 *   ]
 * }
 * ```
 * `version` 字段留给后续格式变更做兼容分支——虽然现在只有一版，
 * 但没有它的话，将来任何字段调整都只能走"解析失败即清空"的暴力路径。
 */
object SourceJsonCodec {

    const val CURRENT_VERSION = 1

    /** 解码结果。 */
    data class Snapshot(
        val entries: List<SourceEntry>,
        val activeId: String?,
        val updatedAt: Long,
        val version: Int
    ) {
        companion object {
            val EMPTY = Snapshot(emptyList(), null, 0L, CURRENT_VERSION)
        }
    }

    /** 序列化。手写拼接，避免构建中间集合。 */
    fun encode(entries: List<SourceEntry>, activeId: String?, updatedAt: Long): String {
        // 每条约 160 字节，一次给够，避免 StringBuilder 扩容拷贝
        val sb = StringBuilder(192 + entries.size * 168)
        sb.append("{\"version\":").append(CURRENT_VERSION)
        sb.append(",\"updatedAt\":").append(updatedAt)
        sb.append(",\"activeId\":")
        if (activeId.isNullOrBlank()) sb.append("null")
        else sb.append('"').append(MiniJson.escape(activeId)).append('"')

        sb.append(",\"entries\":[")
        for (index in entries.indices) {
            if (index > 0) sb.append(',')
            val e = entries[index]
            sb.append("{\"id\":\"").append(MiniJson.escape(e.id)).append('"')
            sb.append(",\"name\":\"").append(MiniJson.escape(e.name)).append('"')
            sb.append(",\"url\":\"").append(MiniJson.escape(e.url)).append('"')
            sb.append(",\"kind\":\"").append(e.kind.name).append('"')
            sb.append(",\"priority\":").append(e.priority)
            sb.append(",\"enabled\":").append(if (e.enabled) "true" else "false")
            sb.append(",\"updatedAt\":").append(e.updatedAt)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 反序列化。
     *
     * 容错策略：**单条不合法只跳过该条，不放弃整个文件**。
     * 用户手动编辑过配置文件、或某次写入被截断时，部分可用远好过全部丢失。
     *
     * @return null 表示内容根本不是合法 JSON（调用方应按"空配置"处理并保留原文件以便排查）
     */
    fun decode(text: String?): Snapshot? {
        if (text.isNullOrBlank()) return Snapshot.EMPTY
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null

        val entriesRaw = root["entries"] as? List<*>
        val entries = entriesRaw?.mapNotNull { it.toSourceEntry() }.orEmpty()

        return Snapshot(
            entries = entries,
            activeId = (root["activeId"] as? String)?.takeIf { it.isNotBlank() },
            updatedAt = (root["updatedAt"] as? Double)?.toLong() ?: 0L,
            version = (root["version"] as? Double)?.toInt() ?: 0
        )
    }

    private fun Any?.toSourceEntry(): SourceEntry? {
        val map = this as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val url = map["url"] as? String ?: return null
        if (url.isBlank()) return null
        return SourceEntry(
            id = id,
            name = map["name"] as? String ?: "未命名配置",
            url = url,
            kind = runCatching { SourceKind.valueOf(map["kind"] as? String ?: "") }
                .getOrDefault(SourceKind.UNKNOWN),
            priority = (map["priority"] as? Double)?.toInt() ?: 0,
            enabled = map["enabled"] as? Boolean ?: true,
            // 老版本文件没有这个字段 → 回落到 0，UI 会显示"尚未更新"而不是崩溃
            updatedAt = (map["updatedAt"] as? Double)?.toLong() ?: 0L
        )
    }
}

/**
 * 极简 JSON 解析/生成器。
 *
 * 只覆盖 JSON 规范里我们真正用得到的部分：object / array / string / number /
 * true / false / null，字符串支持完整转义（含 `\uXXXX`）。
 *
 * 刻意**不**支持：重复键（后者覆盖前者）、超长数字精度（一律按 Double 处理，
 * 我们的数值只有 priority 与时间戳，均在 Double 精确整数范围内）。
 */
internal object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun escape(raw: String): String {
        var needsEscape = false
        for (c in raw) {
            if (c == '"' || c == '\\' || c < ' ' || c == '\u007F') { needsEscape = true; break }
        }
        if (!needsEscape) return raw

        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == '\u007F') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /** @return `Map<String, Any?>` / `List<Any?>` / `String` / `Double` / `Boolean` / `null` */
    fun parse(text: String): Any? = Parser(text).parseDocument()

    private class Parser(private val src: String) {

        private var pos = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos < src.length) throw JsonException("尾部存在多余内容 @$pos")
            return value
        }

        private fun parseValue(): Any? {
            if (pos >= src.length) throw JsonException("内容意外结束")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw JsonException("非法的值起始字符 '$c' @$pos")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>(8)
            pos++ // '{'
            skipWhitespace()
            if (peekIs('}')) { pos++; return result }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when {
                    peekIs(',') -> pos++
                    peekIs('}') -> { pos++; return result }
                    else -> throw JsonException("对象缺少 , 或 } @$pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val result = ArrayList<Any?>(8)
            pos++ // '['
            skipWhitespace()
            if (peekIs(']')) { pos++; return result }
            while (true) {
                skipWhitespace()
                result.add(parseValue())
                skipWhitespace()
                when {
                    peekIs(',') -> pos++
                    peekIs(']') -> { pos++; return result }
                    else -> throw JsonException("数组缺少 , 或 ] @$pos")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val sb = StringBuilder(24)
            while (true) {
                if (pos >= src.length) throw JsonException("字符串未闭合")
                when (val c = src[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (pos >= src.length) throw JsonException("转义符后内容缺失")
                        when (val esc = src[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= src.length) throw JsonException("\\u 转义不完整")
                                val hex = src.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("非法 \\u 转义：$hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("未知转义 \\$esc")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (peekIs('-')) pos++
            while (pos < src.length) {
                val c = src[pos]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
            }
            val raw = src.substring(start, pos)
            return raw.toDoubleOrNull() ?: throw JsonException("非法数字：$raw")
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun peekIs(expected: Char): Boolean = pos < src.length && src[pos] == expected

        private fun expectChar(expected: Char) {
            if (pos >= src.length || src[pos] != expected) {
                throw JsonException("期望 '$expected' @$pos")
            }
            pos++
        }

        private fun expectLiteral(literal: String) {
            if (!src.startsWith(literal, pos)) throw JsonException("期望 $literal @$pos")
            pos += literal.length
        }
    }
}
