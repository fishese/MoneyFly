package com.fishese.moneyfly.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fishese.moneyfly.capture.ReceiptDigester
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Device-local AndroidKeyStore HMAC key; never placed in the database or exported. */
class AndroidReceiptDigester : ReceiptDigester {
    private val key: SecretKey by lazy {
        synchronized(AndroidReceiptDigester::class.java) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build())
                generateKey()
            }
        }
    }
    override fun digest(canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        for (char in canonical) { mac.update((char.code ushr 8).toByte()); mac.update(char.code.toByte()) }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
    private companion object { const val ALIAS = "moneyfly.next.receipt.hmac.v1" }
}
