package edu.jxslu.schedule.data.ykt

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 校园卡登录凭证加密存储（DESIGN §4.19）。
 *
 * 「校园卡付款码」开启时才写入：用户显式录入学号 + 密码，仅用于本机向一卡通平台登录。
 * 方案与 [edu.jxslu.schedule.data.jw.JwCredentialStore] 同款（EncryptedSharedPreferences，
 * 密钥在 Android Keystore），`backup_rules` / `data_extraction_rules` 把本文件排除出
 * 云备份与设备迁移——凭证不随备份体系走。
 */
class YktCredentialStore(context: Context) {

    data class Credentials(val username: String, val password: String)

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ykt_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): Credentials? {
        val user = prefs.getString(KEY_USERNAME, null)?.trim().orEmpty()
        val pwd = prefs.getString(KEY_PASSWORD, null).orEmpty()
        if (user.isEmpty() || pwd.isEmpty()) return null
        return Credentials(user, pwd)
    }

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    /** 清除凭证（关闭开关时调用）。 */
    fun clear() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
