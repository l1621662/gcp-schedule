package edu.jxslu.schedule.ui.common

import android.graphics.Bitmap
import com.google.zxing.common.BitMatrix

/**
 * BitMatrix → Bitmap 的共用渲染（DESIGN §4.19：校园卡与共享单车出码共用，不复制）。
 *
 * 黑白两色取当前主题的前景/背景（深色主题出「深底白码」：微信/相机扫一扫对反色码
 * 兼容良好，且与页面观感一致）。矩阵生成在 domain 层（纯 JVM），本函数只做着色。
 */
object CodeBitmaps {

    fun render(matrix: BitMatrix, dark: Boolean): Bitmap {
        val fg = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val bg = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bitmap.setPixel(x, y, if (matrix[x, y]) fg else bg)
            }
        }
        return bitmap
    }
}
