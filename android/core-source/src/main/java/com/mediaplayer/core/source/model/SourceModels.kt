package com.mediaplayer.core.source.model

import java.util.UUID

/**
 * 源配置类型。
 *
 * 对应客户端需要接入的三类内容源，解析契约参考 FongMi/TV 的 catvod 体系：
 * - [SINGLE] 单仓：一份 JSON 同时描述点播站点（sites）与 Spider 实现（spider）
 * - [MULTI]  多仓：一份 JSON 作为索引，指向若干单仓（storeHouse / urls）
 * - [LIVE]   直播：M3U 播放列表或 TXT 频道表
 */
enum class SourceKind(val label: String) {
    SINGLE("单仓"),
    MULTI("多仓"),
    LIVE("直播"),
    UNKNOWN("未知");

    companion object {
        /**
         * 从外部标签或 URL 推断类型。
         *
         * 优先信任调用方给出的显式标签（用户在 UI 上选的那一项），
         * 标签缺失时才退回按 URL 后缀猜测——猜测结果只用于预筛，
         * 最终类型以 [com.mediaplayer.core.source.parser.SourceProbe] 的实际解析结果为准。
         */
        fun from(labelOrNull: String?, url: String): SourceKind {
            val label = labelOrNull?.trim().orEmpty()
            when {
                label.contains("多仓") || label.equals("multi", ignoreCase = true) -> return MULTI
                label.contains("直播") || label.equals("live", ignoreCase = true) -> return LIVE
                label.contains("单仓") || label.equals("single", ignoreCase = true) -> return SINGLE
            }
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".m3u") || path.endsWith(".m3u8") || path.endsWith(".txt") -> LIVE
                path.endsWith(".json") || path.endsWith(".js") -> SINGLE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 优先级源池中的一条源。
 *
 * [id] 必须在池内稳定且唯一：UI 用它做列表 key，切换逻辑用它判断
 * "新旧源是否为同一个"，因此不要用 url 直接充当 id（同一地址可能有多份不同备注的配置）。
 */
data class SourceEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val kind: SourceKind,
    /** 越小越优先，0 表示 Primary。 */
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** 最后一次修改时间（毫秒）。V3.1 原型卡片上的"更新 今天 12:30"取自这里。 */
    val updatedAt: Long = 0L
) {
    val isPrimary: Boolean get() = priority == 0

    /** 掩码化 URL，用于日志与 UI 展示，避免把完整地址（可能含凭据）写进日志。 */
    val maskedUrl: String
        get() = url.replace(Regex("(//[^/]+/).*"), "${'$'}1…")
}

/**
 * 优先级源池：Primary + 若干 Backups。
 *
 * [ordered] 已经是"可直接按顺序尝试"的列表，失败转移逻辑不需要再做排序，
 * 避免在热路径上重复排序。
 */
data class SourcePool(val entries: List<SourceEntry> = emptyList()) {

    /** 按优先级升序、去重、过滤停用项后的待尝试序列。 */
    val ordered: List<SourceEntry> = entries
        .asSequence()
        .filter { it.enabled }
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.priority }, { it.name }))
        .toList()

    val primary: SourceEntry? get() = ordered.firstOrNull()

    val isEmpty: Boolean get() = ordered.isEmpty()

    /** 把 [entry] 提升为 Primary（priority 归 0），其余顺序顺延。用于"手动指定主源"。 */
    fun promoteToPrimary(entry: SourceEntry): SourcePool {
        if (entry.id !in entries.map { it.id }) return this
        val others = ordered.filterNot { it.id == entry.id }
        val rebuilt = listOf(entry.copy(priority = 0, enabled = true))
            .plus(others.mapIndexed { index, e -> e.copy(priority = index + 1) })
        return copy(entries = rebuilt)
    }
}

/**
 * 单个源探测失败的原因。
 *
 * 之所以要细分而不是只留一个 message，是因为 UI 需要据此给出不同的修复建议：
 * HTTP 403/404 提示"源已失效，请更换链接"，Schema 违规提示"该链接不是有效的源配置"。
 */
sealed interface FailReason {
    val message: String

    /** 服务端明确返回了非 2xx 状态码。 */
    data class Http(val code: Int) : FailReason {
        override val message: String get() = "HTTP $code"
    }

    /** 超时。区分阶段便于判断是"网络不通"还是"源响应慢"。 */
    data class Timeout(val phase: Phase) : FailReason {
        enum class Phase { CONNECT, READ, CALL }
        override val message: String get() = "超时（$phase）"
    }

    /** 连上了、也拿到了内容，但结构不符合该类源的契约（如单仓缺少 sites）。 */
    data class Schema(val detail: String) : FailReason {
        override val message: String get() = "结构不符：$detail"
    }

    /** 建立连接前的网络层异常：DNS 解析失败、连接被拒、TLS 握手失败等。 */
    data class Network(val detail: String) : FailReason {
        override val message: String get() = "网络异常：$detail"
    }

    /** 响应体为空或有效内容为零。 */
    data class Empty(val detail: String) : FailReason {
        override val message: String get() = "空内容：$detail"
    }

    /** 内容超过安全上限，主动中断。低内存设备上这是防 OOM 的第一道闸。 */
    data class TooLarge(val bytes: Long) : FailReason {
        override val message: String get() = "内容超限：${bytes / 1024}KB"
    }
}

/**
 * 解析成功的结构化摘要。
 *
 * 这里刻意**不**持有完整 JSON 对象树：低配盒子（1~2G RAM）上，
 * 一份 3MB 的多仓索引全量反序列化会产生上百个临时对象，是 OOM 的常见来源。
 * 只保留 UI 与切换逻辑真正需要的计数与关键字段。
 */
sealed interface ParseResult {

    data class Ok(
        val kind: SourceKind,
        /** 单仓：sites 数量；多仓：子仓数量；直播：频道数量。 */
        val itemCount: Int,
        /** 单仓的 spider 地址（Java Jar / Python / JS 入口），缺省为 null。 */
        val spider: String?,
        /** 多仓的候选单仓地址；单仓/直播为空。 */
        val childUrls: List<String> = emptyList(),
        /** 多仓索引里声明的仓名，仅用于日志。 */
        val declaredName: String? = null,
        /** 实际读取的字节数，用于诊断"源体积异常膨胀"。 */
        val bytesRead: Long = 0L
    ) : ParseResult

    data class Invalid(val reason: FailReason) : ParseResult
}

/**
 * 轮换成功后的结果快照。
 */
data class ResolvedSource(
    val entry: SourceEntry,
    val detail: ParseResult.Ok,
    /** 从发起请求到解析完成的总耗时，即 UI 上那个 "38ms" 的来源。 */
    val pingMs: Long
)

/**
 * 由局域网投源服务器接收到的推送。
 *
 * 这是 [com.mediaplayer.core.source.push.LocalPushServer] 与客户端之间的契约对象：
 * 服务器只负责"收到并校验形态"，是否入库、是否设为当前源由客户端的业务层决定。
 */
data class PushedSource(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val name: String? = null,
    val kind: SourceKind = SourceKind.UNKNOWN,
    val pushedAt: Long = System.currentTimeMillis(),
    /** 推送方 IP，仅用于日志与审计。 */
    val clientIp: String? = null
)
