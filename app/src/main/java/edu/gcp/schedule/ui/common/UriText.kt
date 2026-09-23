package edu.gcp.schedule.ui.common

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF Uri → UTF-8 文本。JSON 文件导入的两个入口（课表页导入弹层、我的 → 课表数据）
 * 共用这一份读取逻辑：此前各写一遍，读取失败的表现（null / 空串）迟早会分叉。
 * 打不开或读取失败返回 null，提示文案由调用方负责。
 */
suspend fun readTextFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()
}
