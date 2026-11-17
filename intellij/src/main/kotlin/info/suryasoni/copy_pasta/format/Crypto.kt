package info.suryasoni.copy_pasta.format

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : Exception("wrong passphrase or damaged data")

/**
 * Passphrase encryption. Layout: `CPv2` | PBKDF2 iterations (int32, big-endian) | salt (16) | iv (12) |
 * AES-256-GCM ciphertext+tag. The key is derived with PBKDF2-HMAC-SHA256.
 *
 * The iteration count travels in the payload, so it can be raised in later versions without breaking readers.
 * GCM is authenticated, so a wrong passphrase or tampered text is detected, never "decrypted" into garbage.
 * The passphrase is never stored.
 */
object Crypto {
    private val MAGIC = "CPv2".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256

    /** OWASP's 2023+ guidance for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 600_000

    /** Upper bound accepted when reading, so crafted text can't make the IDE spin for minutes. */
    private const val MAX_ITERATIONS = 5_000_000

    private val random = SecureRandom()

    /**
     * False when [passphrase] contains an unpaired UTF-16 surrogate. The JDK would encode it as `?` for the key, while
     * other implementations encode it differently or can't represent it at all, so the text could never be decrypted
     * elsewhere.
     */
    fun isWellFormed(passphrase: CharArray): Boolean {
        var i = 0
        while (i < passphrase.size) {
            val c = passphrase[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= passphrase.size || !Character.isLowSurrogate(passphrase[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(c) -> return false
                else -> i++
            }
        }
        return true
    }

    fun encrypt(data: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(isWellFormed(passphrase)) { "passphrase contains an unpaired surrogate" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(GCM_TAG_BITS, iv))
        val header = ByteBuffer.allocate(MAGIC.size + Int.SIZE_BYTES).put(MAGIC).putInt(iterations).array()
        return header + salt + iv + cipher.doFinal(data)
    }

    fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        val iterationsStart = MAGIC.size
        val saltStart = iterationsStart + Int.SIZE_BYTES
        val ivStart = saltStart + SALT_BYTES
        val dataStart = ivStart + IV_BYTES
        // Too short to hold even the 16-byte GCM tag: reject before the (deliberately slow) key derivation.
        if (payload.size < dataStart + GCM_TAG_BITS / 8 || !payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw InvalidArchiveException("not an encrypted Copy-Pasta payload")
        }
        val iterations = ByteBuffer.wrap(payload, iterationsStart, Int.SIZE_BYTES).int
        if (iterations !in 1..MAX_ITERATIONS) throw InvalidArchiveException("unsupported key derivation settings")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(passphrase, payload.copyOfRange(saltStart, ivStart), iterations)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(ivStart, dataStart)))
        return try {
            cipher.doFinal(payload, dataStart, payload.size - dataStart)
        } catch (_: AEADBadTagException) {
            throw WrongPassphraseException()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
