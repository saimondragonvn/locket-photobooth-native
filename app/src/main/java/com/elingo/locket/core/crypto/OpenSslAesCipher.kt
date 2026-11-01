package com.elingo.locket.core.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-for-byte compatible implementation of CryptoJS.AES.encrypt(plaintext, passphrase).toString()
 * used by https://locket.elingo.app/locket/login (module 1652 in main.2c6ded00.js).
 *
 * Format: Base64_NO_WRAP( "Salted__" (8 bytes ASCII) + salt (8 bytes) + AES-256-CBC(plaintext) )
 * Key & IV derivation: OpenSSL EVP_BytesToKey using MD5 (32-byte key + 16-byte IV).
 */
object OpenSslAesCipher {

    private const val LOCKET_PASSPHRASE = "WNqSuwF5Pz1Rp6FTtFwooT3ZzxgbErwl"
    private val SALTED_PREFIX = "Salted__".toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()

    data class EncryptedCredentials(
        val encryptedEmail: String,
        val encryptedPassword: String
    )

    fun encryptCredentials(email: String, password: String): EncryptedCredentials {
        return EncryptedCredentials(
            encryptedEmail = encrypt(email.trim(), LOCKET_PASSPHRASE),
            encryptedPassword = encrypt(password, LOCKET_PASSPHRASE)
        )
    }

    fun encrypt(plaintext: String, passphrase: String = LOCKET_PASSPHRASE): String {
        val salt = ByteArray(8).also { secureRandom.nextBytes(it) }
        val passphraseBytes = passphrase.toByteArray(Charsets.UTF_8)

        val (keyBytes, ivBytes) = evpBytesToKeyMd5(
            password = passphraseBytes,
            salt = salt,
            keyLen = 32,
            ivLen = 16
        )

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes)
        )
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val openSslPayload = ByteArray(SALTED_PREFIX.size + salt.size + cipherBytes.size)
        System.arraycopy(SALTED_PREFIX, 0, openSslPayload, 0, SALTED_PREFIX.size)
        System.arraycopy(salt, 0, openSslPayload, SALTED_PREFIX.size, salt.size)
        System.arraycopy(cipherBytes, 0, openSslPayload, SALTED_PREFIX.size + salt.size, cipherBytes.size)

        return Base64.encodeToString(openSslPayload, Base64.NO_WRAP)
    }

    private fun evpBytesToKeyMd5(
        password: ByteArray,
        salt: ByteArray,
        keyLen: Int,
        ivLen: Int
    ): Pair<ByteArray, ByteArray> {
        val totalLen = keyLen + ivLen
        val derived = ByteArray(totalLen)
        val md5 = MessageDigest.getInstance("MD5")

        var generated = 0
        var prevBlock = ByteArray(0)

        while (generated < totalLen) {
            md5.reset()
            if (prevBlock.isNotEmpty()) {
                md5.update(prevBlock)
            }
            md5.update(password)
            md5.update(salt)
            prevBlock = md5.digest()

            val toCopy = minOf(prevBlock.size, totalLen - generated)
            System.arraycopy(prevBlock, 0, derived, generated, toCopy)
            generated += toCopy
        }

        val key = derived.copyOfRange(0, keyLen)
        val iv = derived.copyOfRange(keyLen, totalLen)
        return key to iv
    }
}

