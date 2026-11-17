package info.suryasoni.copy_pasta.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {
    private val data = "secret source".toByteArray()

    @Test
    fun `round trip with the right passphrase`() {
        val encrypted = Crypto.encrypt(data, "correct horse".toCharArray())
        assertArrayEquals(data, Crypto.decrypt(encrypted, "correct horse".toCharArray()))
    }

    @Test
    fun `wrong passphrase and tampering are detected`() {
        val encrypted = Crypto.encrypt(data, "correct horse".toCharArray())
        assertThrows(WrongPassphraseException::class.java) { Crypto.decrypt(encrypted, "wrong".toCharArray()) }

        val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(WrongPassphraseException::class.java) { Crypto.decrypt(tampered, "correct horse".toCharArray()) }
    }

    @Test
    fun `same input encrypts differently each time (random salt and IV)`() {
        val a = Crypto.encrypt(data, "p".toCharArray())
        val b = Crypto.encrypt(data, "p".toCharArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `non-encrypted input is rejected as invalid, not as a wrong passphrase`() {
        assertThrows(InvalidArchiveException::class.java) { Crypto.decrypt(ByteArray(100), "p".toCharArray()) }
    }
}
