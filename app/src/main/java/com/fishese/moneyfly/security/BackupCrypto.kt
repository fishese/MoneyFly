package com.fishese.moneyfly.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated encryption. The header is authenticated and costs are bounded on import. */
object BackupCrypto {
    private val magic = byteArrayOf(0x4d, 0x46, 0x4c, 0x59, 0x42, 0x4b, 0x01, 0x00)
    private const val ITERATIONS = 310_000
    const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + NONCE_BYTES

    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty() && data.size <= MAX_PLAINTEXT_BYTES)
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.write(magic); it.writeInt(ITERATIONS); it.write(salt); it.write(nonce) }
        }.toByteArray()
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce, ITERATIONS)
        cipher.updateAAD(header)
        return header + cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty() && data.size in (HEADER_BYTES + 16)..(MAX_PLAINTEXT_BYTES + HEADER_BYTES + 16))
        val header = data.copyOfRange(0, HEADER_BYTES)
        val input = DataInputStream(ByteArrayInputStream(header))
        val actualMagic = ByteArray(magic.size).also(input::readFully)
        require(actualMagic.contentEquals(magic)) { "Unsupported backup format" }
        val iterations = input.readInt()
        require(iterations in 100_000..1_000_000) { "Unsupported backup parameters" }
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
        val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce, iterations)
        cipher.updateAAD(header)
        return cipher.doFinal(data, HEADER_BYTES, data.size - HEADER_BYTES)
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, iterations: Int): Cipher {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) } }
        finally { key.fill(0) }
    }
}
