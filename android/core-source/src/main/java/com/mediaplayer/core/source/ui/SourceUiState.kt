package com.mediaplayer.core.source.ui

import com.mediaplayer.core.source.model.SourceKind

/**
 * 源配置页（`源配置管理`）的单一 UI 状态。
 *
 * 这是双端共用的**唯一渲染输入**：leanback 与 mobile 两个 flavor 各自把它翻译成
 * 「焦点卡片轨道」或「左滑列表」，但都从这个对象取数——UI 不持有任何额外状态，
 * 也就不存在两端行为漂移。
 *
 * 所有字段都是不可变值，配合 `StateFlow` 天然线程安全。
 */
data class SourceUiState(
    /** 已按优先级排序，UI 直接按序渲染即可。 */
    val configs: List<SourceItemUiModel> = emptyList(),

    /** 当前生效源 id；无则为 null。 */
    val currentActiveId: String? = null,

    /**
     * 无痕模式开关状态。
     * 对应 V3.1 原型：true 时全站点缀色切到极光紫、顶栏显示"无痕播放中"。
     */
    val isIncognito: Boolean = false,

    /** 局域网投源地址（含 token），用于大屏展示二维码；服务未启动或地址不可得时为 null。 */
    val pushServerUrl: String? = null,

    val pushServerRunning: Boolean = false,

    /**
     * 自动切源提示文案。
     *
     * 这是一次性语义，但用状态承载：UI 消费后必须回调 [SourceViewModel.consumeSwitchNotice]
     * 清空，避免旋转屏幕后重复弹同一个 Toast。
     */
    val lastSwitchedNotice: String? = null
) {

    val activeConfig: SourceItemUiModel? get() = configs.firstOrNull { it.id == currentActiveId }

    val isEmpty: Boolean get() = configs.isEmpty()

    /** 与原型 `3 个配置` 对应的计数文案。 */
    val countLabel: String get() = "${configs.size} 个配置"

    /** 与原型页码指示 `4 / 7` 对应；总数 ≤3（一屏放得下）时返回 null，UI 不显示。 */
    fun positionLabel(index: Int): String? =
        if (configs.size > VISIBLE_CAPACITY) "${index + 1} / ${configs.size}" else null

    /** 空态下操作条应禁用的动作（对应原型里 `设为当前 / 检测更新 / 删除` 置灰）。 */
    val actionableConfig: SourceItemUiModel? get() = activeConfig ?: configs.firstOrNull()

    companion object {
        /** 电视端一屏能完整放下的卡片数，超过即出现横向滚动与页码。 */
        const val VISIBLE_CAPACITY = 3
    }
}

/**
 * 单张配置卡片的渲染模型。
 *
 * 字段与 V3.1 原型卡片逐项对应：
 * ```
 * ┌──────────────────────────┐
 * │ [类型图标]      [38ms][可用] │  ← kind / pingMs+pingLevel / status
 * │ 我的直播源        [当前]    │  ← name / isActive
 * │ https://example.com/…     │  ← url（UI 自行省略）
 * │ 直播          更新 今天 12:30│  ← kindLabel / updatedAtLabel
 * └──────────────────────────┘
 * ```
 */
data class SourceItemUiModel(
    val id: String,
    val name: String,
    val url: String,
    val kind: SourceKind,
    /** "单仓" / "多仓" / "直播"，已本地化，UI 不必再做映射。 */
    val kindLabel: String,
    val status: SourceStatus,
    /** 探测延迟毫秒；null 表示尚未测速或已超时。 */
    val pingMs: Int?,
    val pingLevel: PingLevel,
    /** "38ms" 或 "超时"，直接可渲染。 */
    val pingLabel: String,
    val isActive: Boolean,
    val enabled: Boolean,
    val updatedAt: Long,
    /** "刚刚" / "今天 12:30" / "3 天前"，避免 UI 层重复实现时间格式化。 */
    val updatedAtLabel: String
)

/** 卡片右侧状态胶囊。 */
enum class SourceStatus(val label: String) {
    AVAILABLE("可用"),
    FAILED("失效"),
    CHECKING("校验中"),
    DISABLED("已停用")
}

/**
 * 延迟语义分级，对应原型里信号条与配色的三档 + 超时。
 *
 * 阈值与设计稿一致：`<80ms 极速` / `80–199ms 良好` / `≥200ms 较慢`。
 * 注意"良好"档的颜色跟随主题轴（标准=电光青、无痕=极光紫），
 * 而"极速"恒为 Mint 绿、"较慢"恒为 Amber——三档语义不该被主题改掉含义。
 */
enum class PingLevel(val label: String) {
    FAST("极速"),
    GOOD("良好"),
    SLOW("较慢"),
    TIMEOUT("超时");

    companion object {
        const val FAST_MAX_MS = 80
        const val GOOD_MAX_MS = 200

        fun of(pingMs: Int?): PingLevel = when {
            pingMs == null -> TIMEOUT
            pingMs < FAST_MAX_MS -> FAST
            pingMs < GOOD_MAX_MS -> GOOD
            else -> SLOW
        }
    }
}

/**
 * 单个源的探测结果。
 *
 * 与 [SourceItemUiModel.pingMs] 的区别在于：这里必须区分
 * **"还没测过"**（Unknown，灰条 + `—`）与 **"测了但失败"**（Failed，灰条 + `超时`）。
 * 只用一个 `Int?` 表达的话，首次进入页面时所有卡片都会显示成"超时"，
 * 用户会误以为全部配置都坏了。
 */
sealed interface ProbeState {

    /** 尚未探测。 */
    data object Unknown : ProbeState

    /** 探测进行中。 */
    data object Checking : ProbeState

    /** 探测成功。 */
    data class Ok(val pingMs: Int) : ProbeState

    /** 探测失败（HTTP 异常 / 连接超时 / 结构不符）。 */
    data object Failed : ProbeState
}

/** 延迟标签文案，对应原型卡片上的 `38ms` / `超时` / `—`。 */
internal fun pingLabelOf(probe: ProbeState): String = when (probe) {
    is ProbeState.Unknown -> "—"
    is ProbeState.Checking -> "…"
    is ProbeState.Ok -> "${probe.pingMs}ms"
    is ProbeState.Failed -> "超时"
}

/** 由条目启用状态 + 探测结果推导卡片状态胶囊。 */
internal fun statusOf(enabled: Boolean, probe: ProbeState): SourceStatus = when {
    !enabled -> SourceStatus.DISABLED
    probe is ProbeState.Checking -> SourceStatus.CHECKING
    probe is ProbeState.Failed -> SourceStatus.FAILED
    else -> SourceStatus.AVAILABLE
}

/** 探测结果 → 延迟分级。未测与失败都归入灰阶（TIMEOUT 档的视觉即灰阶）。 */
internal fun pingLevelOf(probe: ProbeState): PingLevel = when (probe) {
    is ProbeState.Ok -> PingLevel.of(probe.pingMs)
    else -> PingLevel.TIMEOUT
}

/**
 * 时间戳 → 相对时间文案。
 *
 * 刻意放在共用层而非 UI 层：两端显示的相对时间必须一致，
 * 而格式化规则本身与 Android 资源无关，纯 Kotlin 即可，便于单元测试。
 */
internal fun relativeTimeLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return "尚未更新"
    val diff = now - timestamp
    if (diff < 0) return "刚刚"

    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L

    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        hours < 24L -> "今天 ${clockLabel(timestamp)}"
        days < 2L -> "昨天 ${clockLabel(timestamp)}"
        days < 30L -> "${days} 天前"
        else -> "${days / 30} 个月前"
    }
}

/**
 * 取时间戳的"时:分"，按**系统时区**计算。
 *
 * 不用 `SimpleDateFormat`（非线程安全，且每次格式化都会新建内部缓冲），
 * 也不硬编码时区偏移——用户在哪个时区，就显示哪里的时间。
 * 这里的调用频率是"每次 UI 重建 × 卡片数"，对象分配可忽略。
 */
private fun clockLabel(timestamp: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = timestamp
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY).toLong()
    val minute = calendar.get(java.util.Calendar.MINUTE).toLong()
    return "${pad2(hour)}:${pad2(minute)}"
}

private fun pad2(value: Long): String = if (value < 10L) "0$value" else value.toString()
