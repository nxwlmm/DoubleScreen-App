package com.mediaplayer.core.source.model

/**
 * 源调度器对 UI 暴露的响应式状态。
 *
 * 设计取舍：把 [Switched] 也建模成"状态"而非"事件"，是为了让 UI 只订阅一个
 * StateFlow 就能画完整个界面——订阅方不需要额外处理一次性事件的"重放"问题
 * （进程重建后 StateFlow 会自动重放最后一帧，UI 不会丢失"刚刚切换过"的信息）。
 *
 * 状态迁移正常路径：
 * ```
 * Idle → Checking(第 1 个源) → Available
 * Idle → Checking(源A) → Checking(源B) → Switched(A→B) → Available
 * Idle → Checking(源A) → Checking(源B) → Failed
 * ```
 */
sealed interface SourceState {

    /** 尚未开始，或已被调用方重置。 */
    data object Idle : SourceState

    /**
     * 正在探测第 [attempt] / [total] 个候选源。
     * 电视端可据此显示 "正在检测 2/5…"。
     */
    data class Checking(
        val entry: SourceEntry,
        val attempt: Int,
        val total: Int
    ) : SourceState

    /**
     * 已找到可用源。
     *
     * @param pingMs 从发起请求到解析完成的总耗时，直接对应 UI 上的延迟标签。
     */
    data class Available(
        val entry: SourceEntry,
        val kind: SourceKind,
        val pingMs: Long,
        val itemCount: Int,
        val spider: String? = null
    ) : SourceState

    /**
     * 发生了一次自动故障转移。[from] 为 null 表示首次选定（此前无可用源）。
     *
     * 这是一个**瞬时**状态：紧接着调度器会发出对应的 [Available]，
     * UI 收到本状态时适合弹一次 Toast / 显示一次切换动画，收到 [Available] 后再落定界面。
     */
    data class Switched(
        val from: SourceEntry?,
        val to: SourceEntry,
        val reason: FailReason,
        val pingMs: Long
    ) : SourceState

    /**
     * 池内所有源都不可用。
     * [tried] 保留完整尝试序列（含失败原因），便于"诊断详情"页面直接渲染。
     */
    data class Failed(
        val tried: List<Attempt>,
        val lastReason: FailReason?
    ) : SourceState {
        data class Attempt(val entry: SourceEntry, val reason: FailReason)
    }
}

/** UI 层便捷判定：是否处于"忙"状态（用于禁用按钮 / 显示转圈）。 */
val SourceState.isBusy: Boolean
    get() = this is SourceState.Checking

/** 当前生效的源，若还没有可用源则为 null。 */
val SourceState.currentEntry: SourceEntry?
    get() = when (this) {
        is SourceState.Available -> entry
        is SourceState.Switched -> to
        is SourceState.Checking -> entry
        else -> null
    }

/** 一行摘要，便于 TV 端在角落做轻量状态提示。 */
val SourceState.brief: String
    get() = when (this) {
        is SourceState.Idle -> "未加载"
        is SourceState.Checking -> "检测中 $attempt/$total"
        is SourceState.Available -> "${entry.name} · ${pingMs}ms"
        is SourceState.Switched -> "已切换至 ${to.name}"
        is SourceState.Failed -> "全部不可用（已试 ${tried.size} 个）"
    }
