package com.mediaplayer.core.source.parser

/**
 * 极简**流式** JSON 扫描器（纯 Kotlin / JVM，不依赖任何 Android API）。
 *
 * ## 为什么必须自己写
 * 早期版本用的是 `android.util.JsonReader`。它在真机上没问题，但在
 * **Android 单元测试**里是一具空壳（`unitTests.isReturnDefaultValues = true`
 * 只会让方法返回 null/0/false），于是 `peek()` 永远返回 null：
 *
 * ```kotlin
 * if (jr.peek() != JsonToken.BEGIN_OBJECT)   // null != BEGIN_OBJECT → true
 *     return ParseResult.Invalid(Schema("JSON 根节点不是对象"))
 * ```
 *
 * 结果是**每一次源探测都被判成"结构不符"**，所有依赖测速的测试集体超时。
 * 这正是 CI 上多个 SourceViewModelTest 失败的根本原因。
 *
 * ## 为什么不能复用 SourceJsonCodec 里的 MiniJson
 * [com.mediaplayer.core.source.data.SourceJsonCodec] 的 MiniJson 是**全量解析**
 * （构造 Map/List 对象树），用于几十条配置的持久化正好；但源配置 JSON 最大可达
 * 4MB，全量反序列化会在低配盒子上产生上百 MB 临时对象。这里必须**流式**：
 * 只数元素个数、只取需要的几个字段，其余一律快速跳过，峰值内存与源体积无关。
 *
 * ## 支持范围
 * 完整支持 JSON 的 object / array / string（含 `\uXXXX` 转义）/ number /
 * true / false / null。不支持：重复键（后者覆盖前者）、超长数字的高精度保留
 * （一律按 Double 处理——我们的数值只有计数与状态码，够用）。
 */
internal class JsonScanner(private val src: String) {

    private var pos = 0

    /** 扫描失败时抛出，由调用方统一转成 [com.mediaplayer.core.source.model.FailReason.Schema]。 */
    class MalformedException(message: String) : RuntimeException(message)

    // ------------------------------------------------------------------ 顶层入口

    /**
     * 扫描一个顶层对象，对每个成员调用 [onMember]。
     *
     * 回调里可以：
     * - 用 [countArrayElements] / [readStringArray] / [readStoreHouse] / [readStringOrNull]
     *   消费当前值（这些方法都会把游标推进到值之后）；
     * - 或者什么都不做 —— 循环结束时会自动跳过该值（见下方实现）。
     */
    fun scanTopLevelObject(onMember: (name: String) -> Unit) {
        skipWhitespace()
        expect('{')
        skipWhitespace()

        if (peekOrNull() == '}') { pos++; return }

        while (true) {
            skipWhitespace()
            val name = readRawString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            val before = pos
            onMember(name)
            // 回调没有消费这个值 → 我们负责跳过它，保证游标总在正确位置
            if (pos == before) skipValue()

            skipWhitespace()
            when (peekOrNull()) {
                ',' -> { pos++; continue }
                '}' -> { pos++; return }
                null -> throw MalformedException("对象未闭合")
                else -> throw MalformedException("对象成员之间期望 , 或 }，实际 '${src[pos]}' @$pos")
            }
        }
    }

    // ------------------------------------------------------------------ 值消费

    /** 数当前数组的元素个数并消费掉整个数组。非数组时跳过并返回 0。 */
    fun countArrayElements(): Int {
        if (peekOrNull() != '[') { skipValue(); return 0 }
        pos++ // '['
        skipWhitespace()
        if (peekOrNull() == ']') { pos++; return 0 }

        var count = 0
        while (true) {
            skipValue()
            count++
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> { pos++; skipWhitespace(); continue }
                ']' -> { pos++; return count }
                null -> throw MalformedException("数组未闭合")
                else -> throw MalformedException("数组元素之间期望 , 或 ] @$pos")
            }
        }
    }

    /** 读字符串数组，最多取前 [limit] 个（其余仍完整跳过，保证游标正确）。 */
    fun readStringArray(limit: Int): List<String> {
        if (peekOrNull() != '[') { skipValue(); return emptyList() }
        pos++
        skipWhitespace()
        if (peekOrNull() == ']') { pos++; return emptyList() }

        val out = ArrayList<String>(minOf(limit, 16))
        while (true) {
            skipWhitespace()
            if (peekOrNull() == '"') {
                val value = readRawString()
                if (out.size < limit && value.isNotBlank()) out.add(value.trim())
            } else {
                skipValue()
            }
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> { pos++; continue }
                ']' -> { pos++; return out }
                null -> throw MalformedException("数组未闭合")
                else -> throw MalformedException("数组元素之间期望 , 或 ] @$pos")
            }
        }
    }

    /**
     * 读 FongMi 风格的多仓索引：
     * ```json
     * { "storeHouse": [ { "sourceName": "仓A", "sourceUrl": "https://..." } ] }
     * ```
     * 兼容两种降级写法：数组里直接放字符串、或子项用 `url` / `name` / `api` 作为 key。
     *
     * @return Pair(子仓地址列表, 第一个仓的展示名)
     */
    fun readStoreHouse(limit: Int): Pair<List<String>, String?> {
        if (peekOrNull() != '[') { skipValue(); return emptyList<String>() to null }
        pos++
        skipWhitespace()
        if (peekOrNull() == ']') { pos++; return emptyList<String>() to null }

        val urls = ArrayList<String>(minOf(limit, 8))
        var firstName: String? = null

        while (true) {
            skipWhitespace()
            when (peekOrNull()) {
                '"' -> {
                    val direct = readRawString().trim()
                    if (direct.startsWith("http") && urls.size < limit) urls.add(direct)
                }
                '{' -> {
                    val child = readChildEntry()
                    if (child.first != null && urls.size < limit) urls.add(child.first!!)
                    if (firstName == null) firstName = child.second
                }
                else -> skipValue()
            }
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> { pos++; continue }
                ']' -> { pos++; return urls to firstName }
                null -> throw MalformedException("storeHouse 数组未闭合")
                else -> throw MalformedException("storeHouse 元素之间期望 , 或 ] @$pos")
            }
        }
    }

    /** 读一个子仓对象，取出 url 与 name。 */
    private fun readChildEntry(): Pair<String?, String?> {
        expect('{')
        skipWhitespace()
        if (peekOrNull() == '}') { pos++; return null to null }

        var url: String? = null
        var name: String? = null
        while (true) {
            skipWhitespace()
            val key = readRawString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            if (peekOrNull() == '"') {
                val value = readRawString()
                when (key) {
                    "sourceUrl", "url", "api" -> if (url == null) url = value.trim()
                    "sourceName", "name" -> if (name == null) name = value.trim()
                }
            } else {
                skipValue()
            }

            skipWhitespace()
            when (peekOrNull()) {
                ',' -> { pos++; continue }
                '}' -> { pos++; break }
                null -> throw MalformedException("子仓对象未闭合")
                else -> throw MalformedException("子仓对象成员之间期望 , 或 } @$pos")
            }
        }
        return url?.takeIf { it.startsWith("http") } to name
    }

    /** 读一个字符串值；当前值不是字符串时跳过并返回 null。 */
    fun readStringOrNull(): String? {
        skipWhitespace()
        return if (peekOrNull() == '"') readRawString() else { skipValue(); null }
    }

    /** 跳过任意 JSON 值（对象 / 数组 / 字符串 / 数字 / 字面量），递归处理嵌套。 */
    fun skipValue() {
        skipWhitespace()
        when (peekOrNull()) {
            '{', '[' -> skipAnyContainer()
            '"' -> readRawString()
            't' -> expectLiteral("true")
            'f' -> expectLiteral("false")
            'n' -> expectLiteral("null")
            null -> throw MalformedException("内容意外结束")
            else -> skipBareToken()
        }
    }

    /**
     * 跳过一整个容器。
     *
     * 关键点：**同时追踪 `{` `}` 和 `[` `]` 四种括号，共用同一个深度计数器**。
     * 早期版本按 `skipContainer('{','}')` 只认一种括号，遇到 `[{"a":1}]` 这种
     * 混合嵌套时，内层的 `{` `}` 会落到 else 分支、不计深度 —— 虽然多数情况
     * 能"凑巧"走对，但只要字符串边界稍有偏差就会提前结束，属于不可靠实现。
     */
    private fun skipAnyContainer() {
        var depth = 0
        while (true) {
            skipWhitespace()
            when (val c = peekOrNull()) {
                null -> throw MalformedException("容器未闭合")
                // 字符串必须整体消费：里面的括号不参与深度计算
                '"' -> readRawString()
                '{', '[' -> { depth++; pos++ }
                '}', ']' -> {
                    depth--
                    pos++
                    if (depth == 0) return
                }
                else -> pos++ // 数字、字面量、逗号、冒号等都不影响深度
            }
        }
    }

    /** 跳过数字或 true/false/null 这类裸 token。 */
    private fun skipBareToken() {
        val start = pos
        while (pos < src.length) {
            val c = src[pos]
            if (c.isLetterOrDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++ else break
        }
        if (pos == start) throw MalformedException("非法值起始字符 '${src[pos]}' @$pos")
    }

    // ------------------------------------------------------------------ 词法

    /** 读取原始字符串（含转义处理）。 */
    private fun readRawString(): String {
        expect('"')
        // 绝大多数 key 与值都不含转义，先走快路径：找到下一个引号即可
        val start = pos
        var i = pos
        var needsSlowPath = false
        while (i < src.length) {
            when (src[i]) {
                '\\' -> { needsSlowPath = true; break }
                '"' -> {
                    val raw = src.substring(start, i)
                    pos = i + 1
                    return raw
                }
                else -> i++
            }
        }
        if (!needsSlowPath) throw MalformedException("字符串未闭合")

        // 慢路径：逐字符处理转义
        pos = start
        val sb = StringBuilder(32)
        while (true) {
            if (pos >= src.length) throw MalformedException("字符串未闭合")
            when (val c = src[pos]) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    if (pos >= src.length) throw MalformedException("转义符后内容缺失")
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
                            if (pos + 4 >= src.length) throw MalformedException("\\u 转义不完整")
                            val hex = src.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: throw MalformedException("非法 \\u 转义：$hex")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw MalformedException("未知转义 \\$esc")
                    }
                    pos++
                }
                else -> { sb.append(c); pos++ }
            }
        }
    }

    private fun expectLiteral(literal: String) {
        if (!src.startsWith(literal, pos)) {
            throw MalformedException("期望 $literal @$pos")
        }
        pos += literal.length
    }

    private fun expect(c: Char) {
        if (pos >= src.length || src[pos] != c) {
            throw MalformedException("期望 '$c' @$pos")
        }
        pos++
    }

    private fun peekOrNull(): Char? = if (pos < src.length) src[pos] else null

    private fun skipWhitespace() {
        while (pos < src.length) {
            when (src[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }
}
