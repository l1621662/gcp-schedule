package edu.jxslu.schedule.ui.ebike

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import edu.jxslu.schedule.domain.EbikeQr
import java.io.File
import java.io.FileOutputStream

/**
 * 共享单车二维码的位图落盘（DESIGN §3.9 / §4.18）。
 *
 * 矩阵生成在 `domain/EbikeQr`（JVM 可测）；着色渲染已抽到
 * `ui/common/CodeBitmaps`（与校园卡付款码共用，DESIGN §4.19），本对象只做 MediaStore 写入。
 */
object EbikeQrBitmaps {

    /**
     * BitMatrix → Bitmap。黑白两色取当前主题的前景/背景（深色主题出「深底白码」：
     * 微信扫一扫对反色码兼容良好，且与页面观感一致）。委托共用实现。
     */
    fun render(matrix: com.google.zxing.common.BitMatrix, dark: Boolean): Bitmap =
        edu.jxslu.schedule.ui.common.CodeBitmaps.render(matrix, dark)

    /**
     * 保存到系统相册（`Pictures/水贝贝`）。API 29+ 走 MediaStore，无需权限；
     * 26–28 走公共目录 File 直写（manifest 已声明 legacy 外存权限）。
     * 返回 null = 成功；非 null = 用户可读的失败原因。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, bikeId: String): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ebike-$bikeId.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/水贝贝",
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return "相册创建失败（系统拒绝写入）"
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                        return "图片编码失败"
                    }
                } ?: return "相册创建失败（系统拒绝写入）"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "水贝贝",
                )
                if (!dir.exists() && !dir.mkdirs()) return "无法创建相册目录"
                val file = File(dir, "ebike-$bikeId.jpg")
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                        return "图片编码失败"
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            "没有相册写入权限"
        } catch (e: Exception) {
            "保存失败：${e.message ?: "未知错误"}"
        }
    }
}
