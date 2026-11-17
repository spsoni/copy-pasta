package info.suryasoni.copy_pasta.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class CryptoTest {
    private val data = "secret source".toByteArray()
    private val fast = 1_000 // keeps the tests quick; the default is checked once below

    @Test
    fun `round trip with the right passphrase at the default strength`() {
        val encrypted = Crypto.encrypt(data, "correct horse".toCharArray())
        assertEquals(Crypto.ITERATIONS, ByteBuffer.wrap(encrypted, 4, 4).int)
        assertArrayEquals(data, Crypto.decrypt(encrypted, "correct horse".toCharArray()))
    }

    @Test
    fun `iteration count travels in the payload`() {
        val encrypted = Crypto.encrypt(data, "p".toCharArray(), iterations = fast)
        assertEquals(fast, ByteBuffer.wrap(encrypted, 4, 4).int)
        assertArrayEquals(data, Crypto.decrypt(encrypted, "p".toCharArray()))
    }

    @Test
    fun `wrong passphrase and tampering are detected`() {
        val encrypted = Crypto.encrypt(data, "correct horse".toCharArray(), iterations = fast)
        assertThrows(WrongPassphraseException::class.java) { Crypto.decrypt(encrypted, "wrong".toCharArray()) }

        val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(WrongPassphraseException::class.java) { Crypto.decrypt(tampered, "correct horse".toCharArray()) }
    }

    @Test
    fun `crafted iteration counts are refused instead of spinning`() {
        val encrypted = Crypto.encrypt(data, "p".toCharArray(), iterations = fast)
        val crafted = encrypted.copyOf().also { ByteBuffer.wrap(it, 4, 4).putInt(Int.MAX_VALUE) }
        assertThrows(InvalidArchiveException::class.java) { Crypto.decrypt(crafted, "p".toCharArray()) }
    }

    @Test
    fun `same input encrypts differently each time (random salt and IV)`() {
        val a = Crypto.encrypt(data, "p".toCharArray(), iterations = fast)
        val b = Crypto.encrypt(data, "p".toCharArray(), iterations = fast)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `non-encrypted input is rejected as invalid, not as a wrong passphrase`() {
        assertThrows(InvalidArchiveException::class.java) { Crypto.decrypt(ByteArray(100), "p".toCharArray()) }
    }
}
