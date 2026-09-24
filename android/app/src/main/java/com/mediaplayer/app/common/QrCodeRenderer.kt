package com.mediaplayer.app.common

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把局域网投源地址渲染成二维码。
 *
 * ## 为什么用 ZXing core 而不是 zxing-android-embedded
 * 后者会带一整套相机 Activity、权限申请与解码流程，而这里只需要
 * "字符串 → 位图"这一个纯函数。`com.google.zxing:core` 是纯 Java 单 jar（约 500KB）、
 * 无 Android 依赖，正好。
 *
 * ## 低内存约束
 * 位图按 ARGB_8888 计算：512×512 ≈ 1MB。不用 1024 是因为电视端二维码的显示尺寸
 * 也就 200dp 上下，再高的分辨率只是在浪费低配盒子的堆。
 * 生成完成后 [Bitmap] 由调用方负责回收（View 持有期间不必主动 recycle）。
 */
object QrCodeRenderer {

    /** 推荐边长。投源面板的二维码卡约 232dp，512px 足以被 1 米外的手机扫到。 */
    const val DEFAULT_SIZE_PX = 512

    /**
     * @param content    二维码内容（即含 token 的完整投源地址）
     * @param sizePx     输出位图边长
     * @param foreground 前景色（暗色），通常取主题轴色
     * @param background 背景色（亮色），通常取面板底色
     * @return 生成的位图；内容为空或生成失败时返回 null（UI 应降级为"显示地址文本"）
     */
    fun render(
        content: String,
        sizePx: Int = DEFAULT_SIZE_PX,
        foreground: Int = Color.BLACK,
        background: Int = Color.WHITE
    ): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null

        return try {
            val hints = mapOf(
                // M 级容错：约 15% 破损仍可识别，在电视屏幕上反光/摩尔纹场景下够用。
                // 用 H 级会让矩阵变密，模块变小反而更难扫。
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // 留 1 模块静默区：四周必须有白边，否则扫码器找不到定位图案
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )

            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )

            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val rowOffset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) foreground else background
                }
            }

            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        } catch (e: WriterException) {
            // 内容过长（超过 QR 容量上限）会走到这里 —— 返回 null 让 UI 降级，
            // 而不是抛给调用方把整个投源面板搞崩
            null
        } catch (e: OutOfMemoryError) {
            // 低内存设备上真实存在：宁可没有二维码，也不能让进程被系统杀掉
            null
        }
    }

    /**
     * 渲染时把前景色与背景色互换。
     *
     * 深色主题下"亮底暗块"最醒目；若后续想做成"暗底亮块"的极客风，
     * 直接调本方法即可——但要注意**不是所有扫码器都支持反色二维码**，
     * 主流机型（微信/iOS 相机）可以，老旧设备未必。当前 UI 用正色。
     */
    fun renderInverted(content: String, sizePx: Int, dark: Int, light: Int): Bitmap? =
        render(content, sizePx, foreground = light, background = dark)
}
