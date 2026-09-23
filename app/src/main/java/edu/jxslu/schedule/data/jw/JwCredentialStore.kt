package edu.jxslu.schedule.data.jw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 教务登录凭证加密存储（DESIGN §4.17）。
 *
 * 调课自动检测（默认关闭）开启时才写入：用户显式录入学号 + 密码，仅用于本机向教务登录。
 * 与第三方服务凭证同款（EncryptedSharedPreferences，
 * 密钥在 Android Keystore），且 `backup_rules` / `data_extraction_rules` 把本文件
 * 排除出云备份与设备迁移——凭证不随备份体系走。
 */
class JwCredentialStore(context: Context) {

    data class Credentials(val username: String, val password: String)

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jw_credentials",
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

    /** 清除凭证（关闭开关 / 用户主动清除时调用）。 */
    fun clear() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
