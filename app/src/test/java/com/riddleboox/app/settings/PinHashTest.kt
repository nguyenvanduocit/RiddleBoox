package com.riddleboox.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PinHashTest {

    @Test
    fun `same pin and salt hash identically`() {
        val salt = PinHash.randomSalt()
        assertArrayEquals(PinHash.hash("1234", salt), PinHash.hash("1234", salt))
    }

    @Test
    fun `different pins hash differently under the same salt`() {
        val salt = PinHash.randomSalt()
        assertNotEquals(
            PinHash.hash("1234", salt).toList(),
            PinHash.hash("4321", salt).toList(),
        )
    }

    @Test
    fun `the same pin hashes differently under different salts`() {
        val a = PinHash.randomSalt()
        val b = PinHash.randomSalt()
        assertNotEquals(
            PinHash.hash("1234", a).toList(),
            PinHash.hash("1234", b).toList(),
        )
    }

    @Test
    fun `two calls to randomSalt do not collide`() {
        assertNotEquals(PinHash.randomSalt().toList(), PinHash.randomSalt().toList())
    }

    @Test
    fun `matches is true for the pin and salt that produced the hash`() {
        val salt = PinHash.randomSalt()
        val hash = PinHash.hash("7890", salt)
        assertTrue(PinHash.matches("7890", salt, hash))
    }

    @Test
    fun `matches is false for a wrong pin`() {
        val salt = PinHash.randomSalt()
        val hash = PinHash.hash("7890", salt)
        assertFalse(PinHash.matches("0987", salt, hash))
    }

    @Test
    fun `matches is false for a hash produced under a different salt`() {
        val salt = PinHash.randomSalt()
        val otherSalt = PinHash.randomSalt()
        val hash = PinHash.hash("7890", salt)
        assertFalse(PinHash.matches("7890", otherSalt, hash))
    }
}
