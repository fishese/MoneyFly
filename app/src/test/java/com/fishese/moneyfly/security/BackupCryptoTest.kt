package com.fishese.moneyfly.security

import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    @Test fun backupsRoundTripAndUseFreshSaltAndNonce() {
        val data = "synthetic backup data".toByteArray()
        val password = "synthetic password".toCharArray()
        val first = BackupCrypto.encrypt(data, password)
        val second = BackupCrypto.encrypt(data, password)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("synthetic backup data"))
        assertArrayEquals(data, BackupCrypto.decrypt(first, password))
    }

    @Test fun wrongPasswordAndModifiedFilesAreRejected() {
        val password = "synthetic password".toCharArray()
        val original = BackupCrypto.encrypt("synthetic data".toByteArray(), password)
        fun rejected(data: ByteArray, secret: CharArray) {
            try { BackupCrypto.decrypt(data, secret); fail("Expected rejection") } catch (_: Exception) { }
        }
        rejected(original, "wrong password".toCharArray())
        rejected(original.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, password)
        rejected(original.copyOf(10), password)
    }
}
