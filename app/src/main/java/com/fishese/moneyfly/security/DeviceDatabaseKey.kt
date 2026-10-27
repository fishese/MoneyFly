package com.fishese.moneyfly.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The database key is wrapped by a device-local key that background capture can use. */
internal class DeviceDatabaseKey(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("database_key_v1", Context.MODE_PRIVATE)
    private val alias = "moneyfly.database.key.v1"

    @Synchronized fun getOrCreate(existingEncryptedDatabase: Boolean): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val encrypted = preferences.getString("wrapped", null)
        val iv = preferences.getString("iv", null)
        val existingKey = store.getKey(alias, null) as? SecretKey
        if (encrypted != null || iv != null || existingEncryptedDatabase) {
            check(encrypted != null && iv != null && existingKey != null) { "The encrypted database key is unavailable" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        }
        val key = existingKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val passphrase = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }.toByteArray(Charsets.US_ASCII)
        bytes.fill(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val wrapped = cipher.doFinal(passphrase)
        check(preferences.edit().putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Could not save the database key" }
        return passphrase
    }
}
