package com.mediaplayer.app.leanback

import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mediaplayer.app.R
import com.mediaplayer.app.common.UiTheme
import com.mediaplayer.app.databinding.ItemSourceCardBinding
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.ui.PingLevel
import com.mediaplayer.core.source.ui.SourceItemUiModel
import com.mediaplayer.core.source.ui.SourceStatus

/**
 * 电视端横向卡片轨道适配器。
 *
 * ## 焦点是这一层唯一的复杂点
 * 电视端没有触摸，所有交互都从"当前焦点在哪张卡"出发。因此本类做了三件事：
 *
 * 1. **焦点即状态**：`focusedIndex` 是操作条的作用目标，每次焦点移动都要回调出去，
 *    否则用户按「设为当前」会操作到上一次聚焦的那张卡。
 * 2. **遥控器按键拦截**：OK/DPAD_CENTER 触发激活、MENU 呼出微面板。
 *    两个键都 `return true` **消费掉事件**，避免同一次按键既走 onKey 又走 onClick
 *    导致操作执行两次。
 * 3. **DiffUtil 刷新**：直接 `notifyDataSetChanged` 会让 RecyclerView 重建全部子 View，
 *    焦点会掉回第一张卡——用户点一次"检测更新"就发现焦点跑掉了。
 */
class SourceCardAdapter(
    /** 主题轴提供者：卡片重绘时需要知道当前是无痕还是标准模式。 */
    private val incognitoProvider: () -> Boolean,
    /** OK / 点击：设为当前源。 */
    private val onActivate: (SourceItemUiModel) -> Unit,
    /** MENU / 长按：呼出该卡片的操作微面板。 */
    private val onMenu: (SourceItemUiModel, View) -> Unit,
    /** 焦点移动回调：用于刷新操作条的目标文案与页码。 */
    private val onFocusMoved: (index: Int) -> Unit
) : RecyclerView.Adapter<SourceCardAdapter.CardHolder>() {

    private val items = ArrayList<SourceItemUiModel>()

    /** 当前焦点索引。UI 层的操作目标以它为准。 */
    var focusedIndex: Int = 0
        private set

    fun submit(newItems: List<SourceItemUiModel>) {
        val diff = DiffUtil.calculateDiff(CardDiff(items, newItems))
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
        if (focusedIndex >= items.size) {
            focusedIndex = (items.size - 1).coerceAtLeast(0)
        }
    }

    fun itemAt(index: Int): SourceItemUiModel? = items.getOrNull(index)

    /** 操作条当前的作用目标：优先取焦点项，空列表时返回 null（调用方据此置灰按钮）。 */
    val actionTarget: SourceItemUiModel? get() = items.getOrNull(focusedIndex)

    /** 主题轴切换后强制重绘可见项（背景是代码构建的，不重绘不会变色）。 */
    fun refreshTheme() {
        notifyItemRangeChanged(0, items.size)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
        CardHolder(ItemSourceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class CardHolder(
        private val binding: ItemSourceCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var bound: SourceItemUiModel? = null

        init {
            binding.root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != RecyclerView.NO_POSITION) {
                    focusedIndex = position
                    onFocusMoved(position)
                }
                applyFocus(hasFocus)
            }

            // 触摸 / 鼠标场景
            binding.root.setOnClickListener { bound?.let(onActivate) }

            /**
             * 遥控器按键。
             *
             * 必须消费掉 DPAD_CENTER/ENTER：View 默认对这两个键会自行触发 performClick，
             * 若此处不返回 true，一次按键会走两条路径，导致"设为当前"被执行两次。
             */
            binding.root.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        bound?.let(onActivate)
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        bound?.let { onMenu(it, view) }
                        true
                    }
                    else -> false
                }
            }
        }

        fun bind(item: SourceItemUiModel) {
            bound = item
            val context = binding.root.context
            val incognito = incognitoProvider()

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
            binding.kindText.text = item.kindLabel
            binding.updatedText.text = item.updatedAtLabel

            // ---- 「当前」标记 ----
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

            // ---- 延迟标签（信号条的亮格数由 drawable 决定，这里只给色） ----
            val pingTone = when (item.pingLevel) {
                PingLevel.FAST -> UiTheme.Tone.PING_FAST
                PingLevel.GOOD -> UiTheme.Tone.PING_GOOD
                PingLevel.SLOW -> UiTheme.Tone.PING_SLOW
                PingLevel.TIMEOUT -> UiTheme.Tone.PING_TIMEOUT
            }
            binding.pingLabel.text = item.pingLabel
            binding.pingLabel.background = UiTheme.pillBackground(context, pingTone, incognito)
            binding.pingLabel.setTextColor(UiTheme.pillTextColor(context, pingTone, incognito))

            // 复用 View 时焦点态需要复位，否则会残留上一张卡的发光
            applyFocus(binding.root.isFocused)

            binding.root.contentDescription = context.getString(R.string.a11y_card, item.name)
        }

        /**
         * 焦点视觉：上浮 6dp + 放大 1.04 + 轴色描边与辉光。
         *
         * 用 260ms expo-out（与原型一致）；失焦用 150ms 更快收回，
         * 让"焦点移动"的节奏感是"稳稳落下"而不是"两边同时动"。
         */
        private fun applyFocus(focused: Boolean) {
            val context = binding.root.context
            binding.cardBody.background =
                UiTheme.cardBackground(context, focused, incognitoProvider())

            val scale = if (focused) SCALE_FOCUSED else 1f
            val lift = if (focused) -UiTheme.dp(context, 6f).toFloat() else 0f

            binding.cardBody.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationY(lift)
                .setDuration(if (focused) DURATION_FOCUS_GAIN else DURATION_FOCUS_LOSS)
                .setInterpolator(
                    if (focused) EXPO_OUT_INTERPOLATOR else DEFAULT_INTERPOLATOR
                )
                .start()
        }
    }

    /** DiffUtil 回调：卡片是低频更新的，逐字段比较即可。 */
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
        const val SCALE_FOCUSED = 1.04f
        const val DURATION_FOCUS_GAIN = 260L
        const val DURATION_FOCUS_LOSS = 150L

        /** 与原型一致的缓动：cubic-bezier(.16,1,.3,1) */
        val EXPO_OUT_INTERPOLATOR = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val DEFAULT_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
