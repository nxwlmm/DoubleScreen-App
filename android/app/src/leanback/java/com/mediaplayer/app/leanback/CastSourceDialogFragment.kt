package com.mediaplayer.app.leanback

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.mediaplayer.app.R
import com.mediaplayer.app.common.AppGraph
import com.mediaplayer.app.common.QrCodeRenderer
import com.mediaplayer.app.common.UiTheme
import com.mediaplayer.app.databinding.DialogCastSourceBinding
import com.mediaplayer.core.source.push.LocalPushServer
import com.mediaplayer.core.source.ui.SourceUiState
import com.mediaplayer.core.source.ui.SourceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 局域网极速投源面板。
 *
 * ## 面板开关 = 服务生命周期
 * 打开时 [SourceViewModel.startPushServer]，关闭时 [SourceViewModel.stopPushServer]。
 * 这个绑定不是随手写的：投源服务一旦常驻监听，6 位短 Token 的枚举窗口就从
 * "几十秒"变成"永久"，而 Token 轮换只发生在服务重启时。把服务生命周期压到
 * "面板打开期间"，是当前 Token 强度下唯一可靠的风险控制手段。
 *
 * ## 二维码生成
 * 512×512 的位图生成在低配盒子上要几十毫秒，放主线程会有一次肉眼可见的掉帧，
 * 因此走 [Dispatchers.Default]，并在回写前检查对话框是否还活着（避免内存泄漏）。
 */
class CastSourceDialogFragment : DialogFragment() {

    private var _binding: DialogCastSourceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SourceViewModel by activityViewModels { AppGraph.viewModelFactory }

    /** 上次渲染的地址：地址没变就不重新生成二维码。 */
    private var renderedUrl: String? = null

    /** 上次应用的主题轴：只在真正变化时重刷配色。 */
    private var appliedIncognito: Boolean? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogCastSourceBinding.inflate(layoutInflater)
        return Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            UiTheme.dp(requireContext(), PANEL_WIDTH_DP),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        observeUiState()
        setupClicks()

        // 面板可见 = 服务运行
        viewModel.startPushServer()
        binding.btnPanelClose.requestFocus()
    }

    override fun onStop() {
        // 面板不可见 = 立刻吊销 Token 并停止监听
        viewModel.stopPushServer()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ 绑定

    private fun setupClicks() {
        binding.btnPanelClose.setOnClickListener { dismiss() }
        binding.btnRefreshUrl.setOnClickListener { viewModel.refreshPushUrl() }
        binding.serviceUrl.setOnClickListener {
            val url = renderedUrl ?: return@setOnClickListener
            copyToClipboard(url)
            Snackbar.make(binding.root, R.string.toast_copy_url, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.surface_pop))
                .show()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: SourceUiState) {
        if (appliedIncognito != state.isIncognito) {
            appliedIncognito = state.isIncognito
            applyThemeAxis(state.isIncognito)
        }

        binding.serviceStatus.text = getString(
            if (state.pushServerRunning) R.string.cast_service_running
            else R.string.cast_service_stopped,
            LocalPushServer.DEFAULT_PORT
        )

        val url = state.pushServerUrl
        if (url == renderedUrl) return
        renderedUrl = url

        binding.qrPlaceholder.isVisible = url == null
        binding.qrImage.isVisible = url != null
        binding.serviceUrl.text = url ?: getString(R.string.cast_url_pending)

        if (url == null) {
            binding.qrImage.setImageDrawable(null)
            return
        }

        val target = url
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                QrCodeRenderer.render(
                    content = target,
                    sizePx = QrCodeRenderer.DEFAULT_SIZE_PX,
                    foreground = Color.BLACK,
                    background = Color.WHITE
                )
            }
            // 对话框可能已经关闭；同时确认地址没被换掉，避免旧图覆盖新图
            if (_binding != null && renderedUrl == target) {
                binding.qrImage.setImageBitmap(bitmap)
            }
        }
    }

    private fun applyThemeAxis(incognito: Boolean) {
        val context = requireContext()
        val accent = UiTheme.accent(context, incognito)

        binding.headerIcon.background =
            UiTheme.tileBackground(context, R.color.accent_standard, incognito)
        binding.headerIconImage.imageTintList = ColorStateList.valueOf(accent)

        binding.castBadge.background = UiTheme.chipBackground(context, false, incognito)
        binding.castBadge.setTextColor(ContextCompat.getColor(context, R.color.text_dim))

        binding.serviceStatus.background = UiTheme.chipBackground(context, true, incognito)
        binding.serviceStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_mint))

        binding.serviceUrl.background = UiTheme.urlBoxBackground(context, incognito)
        binding.serviceUrl.setTextColor(ContextCompat.getColor(context, R.color.text_main))

        listOf(binding.btnRefreshUrl, binding.btnPanelClose).forEach { button ->
            styleDialogButton(button)
        }
    }

    private fun styleDialogButton(textView: android.widget.TextView) {
        textView.background = UiTheme.ghostButtonBackground(requireContext())
        textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_sub))
    }

    private fun copyToClipboard(url: String) {
        val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        manager.setPrimaryClip(ClipData.newPlainText("push-url", url))
    }

    companion object {
        const val TAG = "CastSourceDialog"
        private const val PANEL_WIDTH_DP = 764f

        fun newInstance(): CastSourceDialogFragment = CastSourceDialogFragment()
    }
}
