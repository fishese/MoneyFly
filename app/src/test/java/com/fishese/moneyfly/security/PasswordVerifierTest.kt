package com.fishese.moneyfly.security

import org.junit.Assert.*
import org.junit.Test

class PasswordVerifierTest {
    @Test fun saltedVerifierAcceptsOnlyTheMatchingPassword() {
        val password = "synthetic password".toCharArray()
        val first = PasswordVerifier.create(password)
        val second = PasswordVerifier.create(password)
        assertFalse(first.salt.contentEquals(second.salt))
        assertTrue(PasswordVerifier.verify(password, first))
        assertFalse(PasswordVerifier.verify("wrong password".toCharArray(), first))
    }
}
