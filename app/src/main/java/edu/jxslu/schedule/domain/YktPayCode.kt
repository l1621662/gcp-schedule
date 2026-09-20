package edu.jxslu.schedule.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 校园卡付款码的码图矩阵（DESIGN §3.10 / §4.19）。纯 JVM，可测。
 *
 * 网页端（`chunk-fa993c88`）的付款码 = 一条 Code128 一维码 + 一个二维码，
 * 两者内容**都是 20 位数字原样编码**——无前缀、无校验位包装。因此 App 侧只要把
 * 同一串数字分别喂给 zxing 的 QR 与 Code128 writer，扫出来的结果与网页端完全一致。
 */
object YktPayCode {

    /** QR 输出像素边长（同共享单车出码口径，扫码距离近，大了不亏）。 */
    const val QR_SIZE_PX = 720

    /** Code128 输出像素宽度；高度 120px 够扫码枪/相机扫一维码。 */
    const val BARCODE_WIDTH_PX = 720
    const val BARCODE_HEIGHT_PX = 120

    data class Matrices(val qr: BitMatrix, val barcode: BitMatrix)

    /**
     * 生成付款码双矩阵。内容必须是纯数字（服务端口径 20 位）；其他内容交由
     * 上游校验，这里不做格式假设——能与不能编码由 zxing 决定。
     */
    fun matrices(code: String): Matrices {
        val qr = QRCodeWriter().encode(
            code,
            BarcodeFormat.QR_CODE,
            QR_SIZE_PX,
            QR_SIZE_PX,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
        )
        val barcode = Code128Writer().encode(
            code,
            BarcodeFormat.CODE_128,
            BARCODE_WIDTH_PX,
            BARCODE_HEIGHT_PX,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        return Matrices(qr, barcode)
    }
}
