package app.shizheng.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai_config_encrypted", Context.MODE_PRIVATE)
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun read(): AiConfig {
        val encoded = preferences.getString("sealed", null) ?: return AiConfig()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        val text = cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()
        return Json.decodeFromString<AiConfig>(text)
    }

    fun write(config: AiConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        check(cipher.iv.size == IV_SIZE)
        val encrypted = cipher.doFinal(Json.encodeToString(config).encodeToByteArray())
        check(preferences.edit().putString("sealed", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }

    private fun secretKey(): SecretKey {
        val stored = keyStore.getKey(ALIAS, null)
        if (stored is SecretKey) return stored
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "shizheng_ai_settings_v1"
        const val IV_SIZE = 12
    }
}
