package com.elingo.locket.core.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * WebCrypto-compatible asymmetric RSA-OAEP-256 login cipher reconstructed via REA
 * from https://locket-file.vercel.app/ (main.434d302f.js, Module 8406, functions `kt` & `_t`).
 *
 * Protocol specification:
 *  1. Fetch X.509 SubjectPublicKeyInfo (`spki` base64) from `GET /locket/login-key` (`alg = "RSA-OAEP-256"`).
 *  2. Encode `email` and `password` as UTF-8 bytes; each field MUST be <= 190 bytes
 *     (for 2048-bit RSA with SHA-256 OAEP overhead: 256 - 2*32 - 2 = 190 bytes).
 *  3. Encrypt independently using RSA-OAEP with SHA-256 for BOTH the main digest AND MGF1 digest!
 *     NOTE: On Android/JVM, `Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")` defaults
 *     MGF1 to SHA-1 unless an explicit `OAEPParameterSpec` with `MGF1ParameterSpec.SHA256` is passed.
 *  4. Base64-encode (`NO_WRAP`) the resulting 256-byte ciphertexts.
 */
object RsaOaepLoginCipher {

    const val MAX_UTF8_BYTES = 190
    private const val LEGACY_OPENSSL_SALTED_PREFIX_B64 = "U2FsdGVkX1"

    private val OAEP_SHA256_SPEC = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT
    )

    fun importSpkiPublicKey(spkiBase64: String): PublicKey {
        val cleanB64 = spkiBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val derBytes = Base64.decode(cleanB64, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(derBytes)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    fun encryptField(publicKey: PublicKey, plaintext: String): String {
        val utf8Bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size > MAX_UTF8_BYTES) {
            throw IllegalArgumentException("Email hoặc mật khẩu quá dài")
        }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256_SPEC)
        val cipherBytes = cipher.doFinal(utf8Bytes)
        return Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
    }

    fun encryptCredentials(
        spkiBase64: String,
        email: String,
        password: String
    ): OpenSslAesCipher.EncryptedCredentials {
        val publicKey = importSpkiPublicKey(spkiBase64)
        return OpenSslAesCipher.EncryptedCredentials(
            encryptedEmail = encryptField(publicKey, email.trim()),
            encryptedPassword = encryptField(publicKey, password)
        )
    }

    /**
     * Exact port of `y = e => !!e && [e.email, e.password].some(v => String(v || "").startsWith("U2FsdGVkX1"))`
     * from Module 4891 (`main.434d302f.js`), used to detect v1 CryptoJS AES credentials requiring `/locket/upgrade-login`.
     */
    fun isLegacyOpenSslAes(value: String?): Boolean {
        return !value.isNullOrBlank() && value.startsWith(LEGACY_OPENSSL_SALTED_PREFIX_B64)
    }

    fun hasLegacyCredentials(creds: OpenSslAesCipher.EncryptedCredentials?): Boolean {
        if (creds == null) return false
        return isLegacyOpenSslAes(creds.encryptedEmail) || isLegacyOpenSslAes(creds.encryptedPassword)
    }
}

