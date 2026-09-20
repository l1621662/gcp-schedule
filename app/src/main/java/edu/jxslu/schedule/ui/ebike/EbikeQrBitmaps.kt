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
 * `ui/common/CodeBitmaps`（与校园卡付款码共用，DESIGN §4.19），本对象只做 MediaStore 写入
 * 与「扫完即焚」的删除执行。
 */
object EbikeQrBitmaps {

    /** 保存结果：成功带待焚毁 key（[EbikeQr.pendingMediaKey]/[EbikeQr.pendingFileKey]）。 */
    sealed interface SaveResult {
        data class Saved(val pendingKey: String) : SaveResult
        data class Failed(val message: String) : SaveResult
    }

    /**
     * BitMatrix → Bitmap。黑白两色取当前主题的前景/背景（深色主题出「深底白码」：
     * 微信扫一扫对反色码兼容良好，且与页面观感一致）。委托共用实现。
     */
    fun render(matrix: com.google.zxing.common.BitMatrix, dark: Boolean): Bitmap =
        edu.jxslu.schedule.ui.common.CodeBitmaps.render(matrix, dark)

    /**
     * 保存到系统相册（`Pictures/水贝贝`）。API 29+ 走 MediaStore，无需权限；
     * 26–28 走公共目录 File 直写（manifest 已声明 legacy 外存权限）。
     * 成功返回 [SaveResult.Saved]（附待焚毁 key，扫完即焚用）；失败返回 [SaveResult.Failed]。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, bikeId: String): SaveResult {
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
                ) ?: return SaveResult.Failed("相册创建失败（系统拒绝写入）")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                        // 编码失败要回收已 insert 的空行：留着就是相册里一张 0 字节
                        // 孤儿图，且没进焚毁记录，永远清不掉
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        return SaveResult.Failed("图片编码失败")
                    }
                } ?: return SaveResult.Failed("相册创建失败（系统拒绝写入）")
                SaveResult.Saved(EbikeQr.pendingMediaKey(uri.toString()))
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "水贝贝",
                )
                if (!dir.exists() && !dir.mkdirs()) return SaveResult.Failed("无法创建相册目录")
                val file = File(dir, "ebike-$bikeId.jpg")
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                        return SaveResult.Failed("图片编码失败")
                    }
                }
                SaveResult.Saved(EbikeQr.pendingFileKey(file.absolutePath))
            }
        } catch (e: SecurityException) {
            SaveResult.Failed("没有相册写入权限")
        } catch (e: Exception) {
            SaveResult.Failed("保存失败：${e.message ?: "未知错误"}")
        }
    }

    /** 本功能落盘的相盘子目录（与 [saveToGallery] 同源，删除校验用）。 */
    private const val GALLERY_SUBDIR = "水贝贝"

    /**
     * 按 key 删除一张已保存的二维码（扫完即焚，DESIGN §3.9）。只处理本功能自己
     * 写入的条目：API 29+ 是自己 insert 的 MediaStore uri（owner 删除免权限免确认）；
     * 26–28 是本功能写的公共目录文件。key 目标再做一次包含性校验——key 唯一
     * 写入方是 [saveToGallery]，校验只防 DataStore 被篡改/损坏后误删别家文件。
     * 返回 true = 已删除；false = 失败/不存在/校验不过（调用方保留 key 下次重试）。
     */
    fun deletePending(context: Context, key: String): Boolean {
        val parsed = EbikeQr.parsePendingKey(key) ?: return false
        return try {
            when (parsed.first) {
                EbikeQr.PendingKind.MediaStore -> if (
                    android.os.Build.VERSION.SDK_INT >= 29
                ) {
                    val uri = android.net.Uri.parse(parsed.second)
                    // 本功能的 MediaStore 条目只会落在 images 集合；host 校验防任意 content uri
                    if (uri.authority != "media" || !uri.path.orEmpty().contains("images")) {
                        false
                    } else {
                        context.contentResolver.delete(uri, null, null) > 0
                    }
                } else {
                    false // 不应出现（29+ 才有 MediaStore key），防御性丢弃
                }
                EbikeQr.PendingKind.FilePath -> {
                    val path = File(parsed.second).canonicalPath
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        GALLERY_SUBDIR,
                    ).canonicalPath
                    // 只删自己目录下的文件（canonicalPath 兼防 ../ 穿越写法）
                    if (!path.startsWith("$dir${File.separatorChar}")) {
                        false
                    } else {
                        File(path).delete()
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
