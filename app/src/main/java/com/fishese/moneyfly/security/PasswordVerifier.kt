package com.fishese.moneyfly.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Stores only a salted password verifier, never the password or a reusable backup key. */
object PasswordVerifier {
    private const val ITERATIONS = 310_000
    data class Record(val salt: ByteArray, val hash: ByteArray)

    fun create(password: CharArray): Record {
        require(password.size in 8..1024)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return Record(salt, derive(password, salt))
    }

    fun verify(password: CharArray, record: Record): Boolean {
        if (password.isEmpty() || password.size > 1024 || record.salt.size != 16 || record.hash.size != 32) return false
        val hash = derive(password, record.salt)
        return try { MessageDigest.isEqual(hash, record.hash) } finally { hash.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
