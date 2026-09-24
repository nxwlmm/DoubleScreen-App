package com.mediaplayer.app.leanback

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mediaplayer.app.R
import com.mediaplayer.app.common.UiTheme
import com.mediaplayer.app.databinding.DialogQuickActionBinding
import com.mediaplayer.core.source.ui.SourceItemUiModel

/**
 * 遥控器菜单键呼出的操作微面板。
 *
 * ## 为什么不用 AlertDialog.setItems
 * 系统列表项在电视上的焦点行为不可控（部分 ROM 上选中态几乎不可见），
 * 而这个面板是电视端唯一的"对单张卡片做操作"的入口，焦点必须清晰。
 * 自定义 Dialog + 显式 `requestFocus()` 可以保证打开时焦点一定落在第一项。
 *
 * ## 按键处理
 * 与卡片一致：DPAD_CENTER / ENTER 都显式消费并 `dismiss()`。
 * 不消费的话，View 默认会再触发一次 performClick，操作会执行两遍
 * （表现为"点一次删除，弹两次确认框"）。
 */
class QuickActionDialog(
    private val activity: Activity,
    private val incognitoProvider: () -> Boolean
) {

    enum class Action { ACTIVATE, MEASURE, DELETE }

    private val binding = DialogQuickActionBinding.inflate(activity.layoutInflater)

    private val dialog = Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun show(item: SourceItemUiModel, onAction: (Action) -> Unit) {
        val incognito = incognitoProvider()

        binding.panelTitle.text = activity.getString(R.string.quick_panel_title, item.name)
        binding.panelHint.setTextColor(ContextCompat.getColor(activity, R.color.text_dim))

        bindAction(binding.actionActivate, danger = false) { onAction(Action.ACTIVATE) }
        bindAction(binding.actionMeasure, danger = false) { onAction(Action.MEASURE) }
        bindAction(binding.actionDelete, danger = true) { onAction(Action.DELETE) }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                UiTheme.dp(activity, PANEL_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 打开即聚焦第一项，用户无需先按一下方向键
            binding.actionActivate.requestFocus()
        }
        dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun bindAction(button: TextView, danger: Boolean, onClick: () -> Unit) {
        val applyVisual = { focused: Boolean ->
            button.background = when {
                danger && focused -> UiTheme.dangerButtonBackground(activity)
                focused -> UiTheme.ghostButtonBackground(activity)
                else -> null
            }
            button.setTextColor(
                when {
                    danger -> ContextCompat.getColor(activity, R.color.accent_danger)
                    focused -> ContextCompat.getColor(activity, R.color.text_main)
                    else -> ContextCompat.getColor(activity, R.color.text_sub)
                }
            )
        }

        applyVisual(button.isFocused)
        button.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> applyVisual(hasFocus) }
        button.setOnClickListener { dismiss(); onClick() }
        button.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                dismiss()
                onClick()
                true
            } else {
                false
            }
        }
    }

    private companion object {
        const val PANEL_WIDTH_DP = 380f
    }
}
