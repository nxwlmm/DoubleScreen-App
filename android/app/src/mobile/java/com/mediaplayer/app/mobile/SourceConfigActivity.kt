package com.mediaplayer.app.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
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
import com.google.android.material.snackbar.Snackbar
import com.mediaplayer.app.R
import com.mediaplayer.app.common.AppGraph
import com.mediaplayer.app.common.UiTheme
import com.mediaplayer.app.databinding.ActivitySourceConfigBinding
import com.mediaplayer.app.databinding.DialogAddSourceBinding
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.ui.SourceItemUiModel
import com.mediaplayer.core.source.ui.SourceUiState
import com.mediaplayer.core.source.ui.SourceViewModel
import kotlinx.coroutines.launch

/**
 * 手机端主界面 · 源配置管理。
 *
 * ## 状态驱动
 * 与 TV 端完全一致：界面不持有业务状态，只消费 [SourceViewModel.uiState]。
 * 差异全在渲染层——TV 是焦点卡片轨道，这里是可左滑的垂直列表。
 *
 * ## 无痕模式
 * 顶栏胶囊绑定 [SourceViewModel.toggleIncognito]。
 * 注意退出无痕会**丢弃无痕期间的临时修改**（回滚到磁盘快照），
 * 提示文案由 ViewModel 统一给出："已退出无痕模式：临时修改已丢弃，已恢复默认配置"。
 * 这个提示必须在退出流程里可见，否则用户会以为"我删的源自己跑回来了"。
 */
class SourceConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceConfigBinding
    private val viewModel: SourceViewModel by viewModels { AppGraph.viewModelFactory }

    private lateinit var adapter: SourceListAdapter

    /** 只在主题轴真正变化时重刷配色。 */
    private var renderedIncognito: Boolean? = null

    /** 新增弹窗当前选中的类型。 */
    private var pendingKind: SourceKind = SourceKind.SINGLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar()
        setupList()

        observeUiState()
        observeEvents()

        viewModel.bootstrap()
    }

    // ------------------------------------------------------------------ 初始化

    private fun setupTopBar() {
        binding.navBack.setOnClickListener { finish() }
        binding.incognitoPill.setOnClickListener { viewModel.toggleIncognito() }
        binding.btnAddTop.setOnClickListener { showAddDialog() }
        binding.btnAddBottom.setOnClickListener { showAddDialog() }
    }

    private fun setupList() {
        adapter = SourceListAdapter(
            incognitoProvider = { viewModel.uiState.value.isIncognito },
            onActivate = { item -> viewModel.setActive(item.id) },
            onMeasure = { item -> viewModel.measurePing(item.id) },
            onDelete = { item -> confirmDelete(item) }
        )

        binding.sourceList.apply {
            layoutManager = LinearLayoutManager(this@SourceConfigActivity)
            adapter = this@SourceConfigActivity.adapter
            itemAnimator = null
            setHasFixedSize(true)
        }
    }

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
                        is SourceViewModel.UiEvent.SourceSwitched ->
                            showUndoNotice(event.entry.name, event.entry.id)
                    }
                }
            }
        }
    }

    private fun render(state: SourceUiState) {
        adapter.submit(state.configs)

        binding.emptyState.isVisible = state.isEmpty
        binding.sourceList.isVisible = !state.isEmpty
        binding.countLabel.text = if (state.isEmpty) {
            getString(R.string.count_configs_none)
        } else {
            getString(R.string.count_configs_swipe, state.configs.size)
        }

        if (renderedIncognito != state.isIncognito) {
            renderedIncognito = state.isIncognito
            bindThemeAxis(state.isIncognito)
            adapter.refreshTheme()
        }

        state.lastSwitchedNotice?.let { notice ->
            showNotice(notice)
            viewModel.consumeSwitchNotice()
        }
    }

    /** 无痕轴渲染：所有跟随主题变化的颜色集中在这里。 */
    private fun bindThemeAxis(incognito: Boolean) {
        val accent = UiTheme.accent(this, incognito)

        binding.incognitoIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.incognitoText.setTextColor(
            if (incognito) ContextCompat.getColor(this, R.color.text_main)
            else ContextCompat.getColor(this, R.color.text_sub)
        )
        binding.incognitoPill.background = UiTheme.chipBackground(this, incognito, incognito)

        binding.btnAddTop.background =
            UiTheme.tileBackground(this, R.color.accent_standard, incognito)
        binding.btnAddTopIcon.imageTintList = ColorStateList.valueOf(accent)

        binding.btnAddBottom.background = UiTheme.ghostButtonBackground(this)
        binding.btnAddBottom.setTextColor(accent)

        binding.tipText.text = getString(
            if (incognito) R.string.mobile_tip_incognito else R.string.mobile_tip
        )
        binding.tipText.background = UiTheme.tileBackground(this, R.color.accent_standard, incognito)
        binding.tipText.setTextColor(ContextCompat.getColor(this, R.color.text_sub))

        binding.emptyIcon.imageTintList = ColorStateList.valueOf(accent)
    }

    // ------------------------------------------------------------------ 交互

    private fun showAddDialog() {
        adapter.closeOpened()

        val dialogBinding = DialogAddSourceBinding.inflate(LayoutInflater.from(this))
        pendingKind = SourceKind.SINGLE
        bindKindOptions(dialogBinding)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val url = dialogBinding.inputUrl.text?.toString().orEmpty().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    showNotice(getString(R.string.toast_url_invalid))
                    return@setPositiveButton
                }
                val name = dialogBinding.inputName.text?.toString().orEmpty().trim()
                viewModel.addConfig(url, pendingKind, name.ifBlank { null })
            }
            .create()

        dialogBinding.btnPaste.setOnClickListener {
            readClipboard()?.let { text ->
                dialogBinding.inputUrl.setText(text)
                showNotice(getString(R.string.toast_pasted))
            }
        }

        dialog.show()
    }

    private fun bindKindOptions(binding: DialogAddSourceBinding) {
        val options = mapOf(
            binding.kindSingle to SourceKind.SINGLE,
            binding.kindMulti to SourceKind.MULTI,
            binding.kindLive to SourceKind.LIVE
        )
        fun render() {
            options.forEach { (view, kind) ->
                val selected = kind == pendingKind
                view.background = if (selected) {
                    UiTheme.chipBackground(this, true, renderedIncognito ?: false)
                } else {
                    UiTheme.ghostButtonBackground(this)
                }
                view.setTextColor(
                    if (selected) UiTheme.accent(this, renderedIncognito ?: false)
                    else ContextCompat.getColor(this, R.color.text_sub)
                )
            }
        }
        options.forEach { (view, kind) ->
            view.setOnClickListener { pendingKind = kind; render() }
        }
        render()
    }

    private fun confirmDelete(item: SourceItemUiModel) {
        adapter.closeOpened()
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_confirm_message, item.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_delete) { _, _ ->
                viewModel.deleteConfig(item.id)
            }
            .show()
    }

    private fun readClipboard(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(this)?.toString()?.trim()
        return text?.takeIf { !TextUtils.isEmpty(it) }
    }

    private fun showNotice(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.surface_pop))
            .setTextColor(ContextCompat.getColor(this, R.color.text_main))
            .show()
    }

    /**
     * 切换成功后的可撤销提示。
     *
     * 对应 V3.1 的结论：单点卡片直接切源是"静默改动全站内容"，
     * 必须给一条能回退的路径，否则误触不可挽回。
     */
    private fun showUndoNotice(name: String, previousId: String?) {
        Snackbar.make(
            binding.root,
            getString(R.string.toast_switched_to, name),
            Snackbar.LENGTH_LONG
        )
            .setBackgroundTint(ContextCompat.getColor(this, R.color.surface_pop))
            .setTextColor(ContextCompat.getColor(this, R.color.text_main))
            .setActionTextColor(UiTheme.accent(this, renderedIncognito ?: false))
            .setAction(R.string.action_undo) { previousId?.let(viewModel::setActive) }
            .show()
    }
}
