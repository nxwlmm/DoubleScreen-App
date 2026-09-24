package com.mediaplayer.app.mobile

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mediaplayer.app.R
import com.mediaplayer.app.common.UiTheme
import com.mediaplayer.app.databinding.ItemSourceConfigBinding
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.ui.PingLevel
import com.mediaplayer.core.source.ui.SourceItemUiModel
import com.mediaplayer.core.source.ui.SourceStatus
import kotlin.math.abs

/**
 * 手机端源配置列表适配器。
 *
 * ## 左滑露出为什么不用 ItemTouchHelper
 * `ItemTouchHelper` 的语义是"滑动即处置"（swipe-to-dismiss），要在它之上实现
 * "滑开停住、等用户点按钮"需要覆写 `onChildDraw` 并自己维护吸附状态，
 * 而它的 `clearView` 会在 item 回收时把位移重置——两者叠加出的 bug 很难调。
 * 本类直接接管 `card_content` 的 `translationX`：状态只有一个 `openedPosition`，
 * 行为完全可预测。
 *
 * ## 与 RecyclerView 滚动的冲突
 * 横向滑动一旦判定成立，立即 `requestDisallowInterceptTouchEvent(true)`，
 * 否则垂直滚动会把横滑事件抢走，表现为"滑到一半卡片突然弹回去"。
 */
class SourceListAdapter(
    private val incognitoProvider: () -> Boolean,
    private val onActivate: (SourceItemUiModel) -> Unit,
    private val onMeasure: (SourceItemUiModel) -> Unit,
    private val onDelete: (SourceItemUiModel) -> Unit
) : RecyclerView.Adapter<SourceListAdapter.CardHolder>() {

    private val items = ArrayList<SourceItemUiModel>()

    /** 当前展开的那一项；NO_POSITION 表示都收着。 */
    private var openedPosition = RecyclerView.NO_POSITION

    private var attachedRecycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attachedRecycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun submit(newItems: List<SourceItemUiModel>) {
        val diff = DiffUtil.calculateDiff(CardDiff(items, newItems))
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
        if (openedPosition >= items.size) openedPosition = RecyclerView.NO_POSITION
    }

    /** 收起所有已展开项（例如弹窗打开前调用）。 */
    fun closeOpened() {
        val position = openedPosition
        openedPosition = RecyclerView.NO_POSITION
        if (position == RecyclerView.NO_POSITION) return
        (attachedRecycler?.findViewHolderForAdapterPosition(position) as? CardHolder)?.closeSilently()
    }

    /** 主题轴切换后强制重绘可见项。 */
    fun refreshTheme() = notifyItemRangeChanged(0, items.size)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
        CardHolder(ItemSourceConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(items[position], position == openedPosition)
    }

    /** 由 ViewHolder 在吸附完成后回调，保证同一时刻只有一项是展开的。 */
    private fun onSwipeOpened(position: Int) {
        val previous = openedPosition
        if (previous == position) return
        openedPosition = position
        if (previous != RecyclerView.NO_POSITION) {
            (attachedRecycler?.findViewHolderForAdapterPosition(previous) as? CardHolder)
                ?.closeSilently()
        }
    }

    private fun onSwipeClosed(position: Int) {
        if (openedPosition == position) openedPosition = RecyclerView.NO_POSITION
    }

    inner class CardHolder(
        private val binding: ItemSourceConfigBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var bound: SourceItemUiModel? = null
        private val revealPx = binding.root.resources.getDimensionPixelSize(R.dimen.mobile_swipe_reveal)
        private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop

        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var opened = false

        init {
            binding.actionUse.setOnClickListener { bound?.let(onActivate) }
            binding.actionMeasure.setOnClickListener { bound?.let(onMeasure) }
            binding.actionDelete.setOnClickListener { bound?.let(onDelete) }

            // 单点卡片 = 快速切换（V3.1 的"单点快速弹窗"简化为直接切换 + 可撤销提示）
            binding.cardContent.setOnClickListener {
                if (opened) {
                    settle(open = false)
                } else {
                    bound?.let(onActivate)
                }
            }

            attachSwipeGesture()
        }

        fun bind(item: SourceItemUiModel, isOpened: Boolean) {
            bound = item
            opened = isOpened
            binding.cardContent.translationX = if (isOpened) -revealPx.toFloat() else 0f

            val context = binding.root.context
            val incognito = incognitoProvider()

            // ---- 卡片背景（无焦点概念，恒为常态） ----
            binding.cardContent.background = UiTheme.cardBackground(context, false, incognito)

            // ---- 类型图标 ----
            val iconRes = when (item.kind) {
                SourceKind.MULTI -> R.drawable.ic_source_multi
                SourceKind.LIVE -> R.drawable.ic_source_live
                else -> R.drawable.ic_source_single
            }
            val tileColorRes = when (item.kind) {
                SourceKind.MULTI -> R.color.accent_violet
                SourceKind.LIVE -> R.color.accent_mint
                else -> R.color.accent_standard
            }
            binding.tileIcon.setImageResource(iconRes)
            binding.tileIcon.imageTintList =
                ColorStateList.valueOf(UiTheme.tileColor(context, tileColorRes, incognito))
            binding.tile.background = UiTheme.tileBackground(context, tileColorRes, incognito)

            // ---- 文本 ----
            binding.nameText.text = item.name
            binding.urlText.text = item.url
            // 手机端不展示类型文字：tile 图标已经用形状+颜色表达，省出的宽度留给延迟与时间，
            // 否则「更新 昨天 20:11」会被挤到换行（V3.1 原型阶段的实测结论）
            binding.updatedText.text = item.updatedAtLabel

            // ---- 当前标记 ----
            binding.currentBadge.isVisible = item.isActive
            if (item.isActive) {
                binding.currentBadge.background = UiTheme.primaryButtonBackground(context, incognito)
                binding.currentBadge.setTextColor(UiTheme.onAccent(context, incognito))
            }

            // ---- 状态胶囊 ----
            val statusTone = when (item.status) {
                SourceStatus.AVAILABLE -> UiTheme.Tone.OK
                SourceStatus.FAILED -> UiTheme.Tone.FAIL
                SourceStatus.CHECKING -> UiTheme.Tone.CHECK
                SourceStatus.DISABLED -> UiTheme.Tone.DISABLED
            }
            binding.statusPill.text = item.status.label
            binding.statusPill.background = UiTheme.pillBackground(context, statusTone, incognito)
            binding.statusPill.setTextColor(UiTheme.pillTextColor(context, statusTone, incognito))

            // ---- 延迟标签 ----
            val pingTone = when (item.pingLevel) {
                PingLevel.FAST -> UiTheme.Tone.PING_FAST
                PingLevel.GOOD -> UiTheme.Tone.PING_GOOD
                PingLevel.SLOW -> UiTheme.Tone.PING_SLOW
                PingLevel.TIMEOUT -> UiTheme.Tone.PING_TIMEOUT
            }
            binding.pingLabel.text = item.pingLabel
            binding.pingLabel.background = UiTheme.pillBackground(context, pingTone, incognito)
            binding.pingLabel.setTextColor(UiTheme.pillTextColor(context, pingTone, incognito))

            // 「更新」按钮的文字跟随主题轴（无痕下变紫）
            binding.actionMeasure.setTextColor(UiTheme.accentAlt(context, incognito))

            binding.root.contentDescription = context.getString(R.string.a11y_card, item.name)
        }

        /** 立即收起（不做动画），用于"另一项被滑开"时的静默复位。 */
        fun closeSilently() {
            opened = false
            binding.cardContent.animate().cancel()
            binding.cardContent.translationX = 0f
        }

        /**
         * 横向滑动手势。
         *
         * 判定顺序很关键：先看纵向位移是否更大，只有确认这是"横滑"才夺走事件流，
         * 否则列表就没法上下滚了。
         */
        @SuppressLint("ClickableViewAccessibility")
        private fun attachSwipeGesture() {
            binding.cardContent.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        dragging = false
                        binding.cardContent.animate().cancel()
                        false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            dragging = true
                            binding.root.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) {
                            val base = if (opened) -revealPx.toFloat() else 0f
                            binding.cardContent.translationX =
                                (base + dx).coerceIn(-revealPx.toFloat(), 0f)
                            true
                        } else {
                            false
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dragging = false
                            binding.root.parent?.requestDisallowInterceptTouchEvent(false)
                            val shouldOpen =
                                binding.cardContent.translationX < -revealPx * OPEN_THRESHOLD
                            settle(shouldOpen)
                            true
                        } else {
                            false // 交回给 onClick
                        }
                    }

                    else -> false
                }
            }
        }

        /** 吸附到展开或收起位置，并同步适配器里的展开状态。 */
        private fun settle(open: Boolean) {
            opened = open
            binding.cardContent.animate()
                .translationX(if (open) -revealPx.toFloat() else 0f)
                .setDuration(SETTLE_DURATION_MS)
                .start()

            val position = bindingAdapterPosition
            if (open) {
                if (position != RecyclerView.NO_POSITION) onSwipeOpened(position)
            } else {
                if (position != RecyclerView.NO_POSITION) onSwipeClosed(position)
            }
        }
    }

    private class CardDiff(
        private val old: List<SourceItemUiModel>,
        private val new: List<SourceItemUiModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].id == new[newPos].id

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos] == new[newPos]
    }

    private companion object {
        /** 滑过操作区宽度的 40% 即吸附展开——与 V3.1 原型的阈值一致。 */
        const val OPEN_THRESHOLD = 0.4f
        const val SETTLE_DURATION_MS = 180L
    }
}
