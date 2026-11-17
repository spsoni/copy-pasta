package info.suryasoni.copy_pasta.format

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : Exception("wrong passphrase or damaged data")

/**
 * Passphrase encryption: `CPv2` | salt (16) | iv (12) | AES-256-GCM ciphertext+tag, with the key derived by
 * PBKDF2-HMAC-SHA256 (100k iterations). GCM is authenticated, so a wrong passphrase or tampered text is
 * detected, never "decrypted" into garbage. The passphrase is never stored.
 */
object Crypto {
    private val MAGIC = "CPv2".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun encrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return MAGIC + salt + iv + cipher.doFinal(data)
    }

    fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        val saltStart = MAGIC.size
        val ivStart = saltStart + SALT_BYTES
        val dataStart = ivStart + IV_BYTES
        if (payload.size <= dataStart || !payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw InvalidArchiveException("not an encrypted Copy-Pasta payload")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(passphrase, payload.copyOfRange(saltStart, ivStart))
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(ivStart, dataStart)))
        return try {
            cipher.doFinal(payload, dataStart, payload.size - dataStart)
        } catch (_: AEADBadTagException) {
            throw WrongPassphraseException()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
