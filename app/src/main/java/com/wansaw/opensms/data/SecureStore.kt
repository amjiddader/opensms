package com.wansaw.opensms.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the device secret and claim token encrypted with an Android Keystore AES-GCM key. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("opensms_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray())
        val blob = c.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            String(c.doFinal(blob, 12, blob.size - 12))
        } catch (_: Exception) {
            // Keystore key lost (e.g. restored/reset): treat as no secret so the app logs in again.
            prefs.edit().remove(name).apply()
            null
        }
    }

    var deviceSecret: String?
        get() = get("device_secret")
        set(v) = put("device_secret", v)
    var claimToken: String?
        get() = get("claim_token")
        set(v) = put("claim_token", v)
    var requestId: String?
        get() = get("request_id")
        set(v) = put("request_id", v)

    fun clearInstallRequest() {
        claimToken = null
        requestId = null
    }

    private companion object {
        const val ALIAS = "opensms_secret_key"
    }
}
