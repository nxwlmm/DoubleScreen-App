package com.mediaplayer.app.leanback

import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mediaplayer.app.R
import com.mediaplayer.app.common.AppGraph
import com.mediaplayer.app.common.UiTheme
import com.mediaplayer.app.databinding.ActivitySourceConfigBinding
import com.mediaplayer.core.source.ui.SourceItemUiModel
import com.mediaplayer.core.source.ui.SourceUiState
import com.mediaplayer.core.source.ui.SourceViewModel
import kotlinx.coroutines.launch

/**
 * 电视端主界面 · 源配置管理。
 *
 * ## 状态驱动
 * 界面**没有任何本地业务状态**——所有渲染输入都来自 [SourceViewModel.uiState]。
 * 收集用 `repeatOnLifecycle(STARTED)`：页面不可见时自动取消订阅，
 * 否则后台仍在跑 `collect`，在低配盒子上就是白白耗电与内存。
 *
 * ## 焦点策略
 * - 进入页面：焦点还给上次选中的卡片（`adapter.focusedIndex`），体验上等价于"焦点记忆"。
 * - 列表为空：焦点直接落到「新增配置」按钮，而不是停在无处可去的卡片区
 *   （这正是 V3.1 修掉的那个"空态焦点断裂"）。
 * - MENU 键在 [dispatchKeyEvent] 全局拦截，无论焦点此刻在哪都能呼出操作面板。
 */
class SourceConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceConfigBinding
    private val viewModel: SourceViewModel by viewModels { AppGraph.viewModelFactory }

    private lateinit var adapter: SourceCardAdapter
    private lateinit var cardLayoutManager: LinearLayoutManager
    private lateinit var quickPanel: QuickActionDialog

    /** 只在主题轴真正变化时才重刷配色，避免每帧都重建 drawable。 */
    private var renderedIncognito: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupCardList()
        setupActionBar()

        observeUiState()
        observeEvents()

        // 加载磁盘配置并做一次全量测速（批量测速会自动跳过停用项）
        viewModel.bootstrap()
    }

    override fun onResume() {
        super.onResume()
        // 布局完成后再定位焦点，否则 findViewByPosition 还拿不到子 View
        binding.cardList.post { restoreFocus() }
    }

    /**
     * 全局按键拦截。
     *
     * MENU 键不做成卡片级的 onKeyListener，是因为遥控器上的菜单键语义是"对当前上下文的操作"，
     * 用户可能正把焦点停在操作条上——那时卡片收不到按键，功能就等于不存在。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    adapter.actionTarget?.let { showQuickPanel(it) } ?: showCastPanel()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_BUTTON_Y -> {
                    // 部分遥控器的"播放"键常被用户当作"确认"来按，容错处理
                    adapter.actionTarget?.let { viewModel.setActive(it.id) }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ 初始化

    private fun setupTabs() {
        binding.tabSettings.isSelected = true
        binding.tabSettings.setTextColor(ContextCompat.getColor(this, R.color.accent_standard))
    }

    private fun setupCardList() {
        adapter = SourceCardAdapter(
            incognitoProvider = { viewModel.uiState.value.isIncognito },
            onActivate = { item -> viewModel.setActive(item.id) },
            onMenu = { item, _ -> showQuickPanel(item) },
            onFocusMoved = { index -> onCardFocused(index) }
        )

        cardLayoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.cardList.apply {
            layoutManager = cardLayoutManager
            adapter = this@SourceConfigActivity.adapter
            // 关掉默认动画：卡片背景是代码构建的 drawable，默认动画会在刷新时闪一下
            itemAnimator = null
            setHasFixedSize(true)
            addItemDecoration(CardSpacingDecoration(UiTheme.dp(this@SourceConfigActivity, 16f)))
        }
    }

    private fun setupActionBar() {
        binding.btnUse.setOnClickListener { adapter.actionTarget?.let { viewModel.setActive(it.id) } }
        binding.btnUpdate.setOnClickListener { adapter.actionTarget?.let { viewModel.measurePing(it.id) } }
        binding.btnDelete.setOnClickListener { adapter.actionTarget?.let { confirmDelete(it) } }
        binding.btnAdd.setOnClickListener { showCastPanel() }

        // 无痕开关（电视端也允许遥控器点，保持与手机端同一套状态）
        binding.tvIncognito.setOnClickListener { viewModel.toggleIncognito() }

        actionButtons().forEach { button ->
            button.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                styleActionButton(view as TextView, hasFocus)
            }
            styleActionButton(button, false)
        }
    }

    private fun actionButtons(): List<TextView> =
        listOf(binding.btnUse, binding.btnUpdate, binding.btnDelete, binding.btnAdd)

    // ------------------------------------------------------------------ 状态绑定

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is SourceViewModel.UiEvent.Toast -> showNotice(event.message)
                        // 手动切源：UI 层负责给出可撤销提示（与 V3.1 原型一致）
                        is SourceViewModel.UiEvent.SourceSwitched ->
                            showUndoNotice(event.entry.name, event.entry.id)
                    }
                }
            }
        }
    }

    private fun render(state: SourceUiState) {
        val wasEmpty = adapter.itemCount == 0

        adapter.submit(state.configs)

        binding.countChip.isVisible = !state.isEmpty
        binding.countChip.text = state.countLabel
        binding.emptyState.isVisible = state.isEmpty
        binding.cardList.isVisible = !state.isEmpty

        val positionLabel = state.positionLabel(adapter.focusedIndex)
        binding.positionLabel.isVisible = positionLabel != null
        binding.positionLabel.text = positionLabel.orEmpty()

        updateActionTarget(state)

        // 主题轴只在真正变化时重刷
        if (renderedIncognito != state.isIncognito) {
            renderedIncognito = state.isIncognito
            bindThemeAxis(state.isIncognito)
            adapter.refreshTheme()
        }

        // 从空态进入有数据：把焦点从「新增配置」交还给卡片
        if (wasEmpty && !state.isEmpty) {
            binding.cardList.post { restoreFocus() }
        }

        // 自动切源提示：用后即清，避免旋转/重建后重复弹
        state.lastSwitchedNotice?.let { notice ->
            showNotice(notice)
            viewModel.consumeSwitchNotice()
        }
    }

    /** 无痕轴渲染。所有"会随主题变化的颜色"都在这一处集中处理。 */
    private fun bindThemeAxis(incognito: Boolean) {
        val accent = UiTheme.accent(this, incognito)

        binding.brandIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.tabSettings.setTextColor(accent)

        binding.countChip.background = UiTheme.pillBackground(this, UiTheme.Tone.PING_GOOD, incognito)
        binding.countChip.setTextColor(accent)

        binding.incognitoIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.incognitoText.text = getString(
            if (incognito) R.string.incognito_active else R.string.incognito_standard_mode
        )
        binding.incognitoText.setTextColor(
            if (incognito) ContextCompat.getColor(this, R.color.text_main)
            else ContextCompat.getColor(this, R.color.text_dim)
        )
        binding.tvIncognito.background = UiTheme.chipBackground(this, incognito, incognito)

        binding.emptyIcon.imageTintList = ColorStateList.valueOf(accent)

        actionButtons().forEach { styleActionButton(it, it.isFocused) }
    }

    private fun styleActionButton(button: TextView, focused: Boolean) {
        val incognito = renderedIncognito ?: false
        when {
            button === binding.btnAdd -> {
                button.background = UiTheme.primaryButtonBackground(this, incognito)
                button.setTextColor(UiTheme.onAccent(this, incognito))
            }
            button === binding.btnDelete -> {
                button.background = if (focused) UiTheme.dangerButtonBackground(this)
                else UiTheme.ghostButtonBackground(this)
                button.setTextColor(ContextCompat.getColor(this, R.color.accent_danger))
            }
            focused -> {
                button.background = UiTheme.ghostButtonBackground(this)
                button.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            }
            else -> {
                button.background = null
                button.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            }
        }
    }

    private fun updateActionTarget(state: SourceUiState) {
        val target = adapter.actionTarget
        binding.actionTarget.text = if (target != null) {
            getString(R.string.tv_action_target, target.name)
        } else {
            getString(R.string.tv_action_target_none)
        }
        val enabled = target != null && !state.isEmpty
        listOf(binding.btnUse, binding.btnUpdate, binding.btnDelete).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    private fun onCardFocused(index: Int) {
        val positionLabel = viewModel.uiState.value.positionLabel(index)
        binding.positionLabel.isVisible = positionLabel != null
        binding.positionLabel.text = positionLabel.orEmpty()
        updateActionTarget(viewModel.uiState.value)
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 焦点恢复。
     *
     * 空列表时把焦点交给「新增配置」——这是 V3.1 修掉的"空态焦点断裂"：
     * 此前空态下提示"按 OK 打开操作条"，但一张卡都没有，焦点无处可落，
     * 用户按 OK 只会在"请先新增配置"上打转。
     */
    private fun restoreFocus() {
        if (adapter.itemCount == 0) {
            binding.btnAdd.requestFocus()
            return
        }
        val position = adapter.focusedIndex.coerceIn(0, adapter.itemCount - 1)
        cardLayoutManager.scrollToPositionWithOffset(position, 0)
        cardLayoutManager.findViewByPosition(position)?.requestFocus()
            ?: binding.cardList.requestFocus()
    }

    private fun showQuickPanel(item: SourceItemUiModel) {
        if (!::quickPanel.isInitialized) {
            quickPanel = QuickActionDialog(this) { viewModel.uiState.value.isIncognito }
        }
        quickPanel.show(item) { action ->
            when (action) {
                QuickActionDialog.Action.ACTIVATE -> viewModel.setActive(item.id)
                QuickActionDialog.Action.MEASURE -> viewModel.measurePing(item.id)
                QuickActionDialog.Action.DELETE -> confirmDelete(item)
            }
        }
    }

    private fun confirmDelete(item: SourceItemUiModel) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_confirm_message, item.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_delete) { _, _ -> viewModel.deleteConfig(item.id) }
            .show()
    }

    private fun showCastPanel() {
        CastSourceDialogFragment.newInstance()
            .show(supportFragmentManager, CastSourceDialogFragment.TAG)
    }

    private fun showNotice(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.surface_pop))
            .setTextColor(ContextCompat.getColor(this, R.color.text_main))
            .show()
    }

    /** 手动切源后的可撤销提示：与 V3.1 原型的"撤销"闭环一致。 */
    private fun showUndoNotice(name: String, previousId: String?) {
        Snackbar.make(
            binding.root,
            getString(R.string.toast_switched_to, name),
            Snackbar.LENGTH_LONG
        )
            .setBackgroundTint(ContextCompat.getColor(this, R.color.surface_pop))
            .setTextColor(ContextCompat.getColor(this, R.color.text_main))
            .setActionTextColor(UiTheme.accent(this, renderedIncognito ?: false))
            .setAction(R.string.action_undo) {
                // 撤销到切换前的源；确切的"上一个"由 UI 记录，这里简单回退到传入的 id
                previousId?.let(viewModel::setActive)
            }
            .show()
    }

    private companion object {
        const val DISABLED_ALPHA = 0.28f
    }
}

/**
 * 卡片间距装饰器。
 *
 * 之所以不用 `MarginLayoutParams` + `notifyItemChanged`：改布局参数会触发整表重新测量，
 * 而 ItemDecoration 只在布局阶段贡献 offset，代价低得多——低配盒子上单帧能省几毫秒。
 */
private class CardSpacingDecoration(private val gapPx: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val half = gapPx / 2
        outRect.left = if (position == 0) 0 else half
        outRect.right = if (position == parent.adapter?.itemCount?.minus(1)) 0 else half
    }
}
