package dev.zain.znkeyboard

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Encodes portable backups without relying on a device-bound Android Keystore key. */
internal object EncryptedBackupCodec {
    const val MIN_PASSWORD_LENGTH = 12

    private const val FORMAT = "dev.zain.znkeyboard.encrypted-backup"
    private const val SCHEMA_VERSION = 1
    private const val KDF_NAME = "PBKDF2WithHmacSHA256"
    private const val CIPHER_NAME = "AES/GCM/NoPadding"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val MIN_CIPHERTEXT_LENGTH_BYTES = 16

    private val secureRandom = SecureRandom()

    fun encrypt(plainText: String, password: CharArray): String {
        try {
            require(password.size >= MIN_PASSWORD_LENGTH) {
                "Use a backup password with at least $MIN_PASSWORD_LENGTH characters."
            }

            val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
            val iv = ByteArray(IV_LENGTH_BYTES).also(secureRandom::nextBytes)
            val saltBase64 = salt.base64()
            val ivBase64 = iv.base64()
            val envelope = JSONObject()
                .put("format", FORMAT)
                .put("schemaVersion", SCHEMA_VERSION)
                .put(
                    "kdf",
                    JSONObject()
                        .put("name", KDF_NAME)
                        .put("iterations", PBKDF2_ITERATIONS)
                        .put("salt", saltBase64),
                )
                .put(
                    "cipher",
                    JSONObject()
                        .put("name", CIPHER_NAME)
                        .put("iv", ivBase64),
                )

            val cipher = Cipher.getInstance(CIPHER_NAME)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveKey(password, salt, PBKDF2_ITERATIONS),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
            cipher.updateAAD(associatedData(PBKDF2_ITERATIONS, saltBase64, ivBase64))
            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return envelope
                .put("ciphertext", ciphertext.base64())
                .toString(2)
        } catch (error: GeneralSecurityException) {
            throw BackupCryptoException("Could not encrypt backup.", error)
        } finally {
            password.fill('\u0000')
        }
    }

    fun decrypt(encryptedBackup: String, password: CharArray): String {
        try {
            if (password.isEmpty()) {
                throw BackupCryptoException("Enter the backup password.")
            }

            val envelope = try {
                JSONObject(encryptedBackup)
            } catch (error: JSONException) {
                throw BackupCryptoException("Not a ZnKeyboard encrypted backup.", error)
            }
            if (envelope.optString("format") != FORMAT ||
                envelope.optInt("schemaVersion", -1) != SCHEMA_VERSION
            ) {
                throw BackupCryptoException("Not a supported ZnKeyboard encrypted backup.")
            }

            val kdf = envelope.optJSONObject("kdf")
                ?: throw BackupCryptoException("Backup is missing encryption details.")
            val cipherDetails = envelope.optJSONObject("cipher")
                ?: throw BackupCryptoException("Backup is missing encryption details.")
            val iterations = kdf.optInt("iterations", -1)
            val saltBase64 = kdf.optString("salt")
            val ivBase64 = cipherDetails.optString("iv")
            if (kdf.optString("name") != KDF_NAME ||
                cipherDetails.optString("name") != CIPHER_NAME ||
                iterations != PBKDF2_ITERATIONS
            ) {
                throw BackupCryptoException("Unsupported backup encryption settings.")
            }

            val salt = saltBase64.decodeBase64()
            val iv = ivBase64.decodeBase64()
            val ciphertext = envelope.optString("ciphertext").decodeBase64()
            if (salt.size != SALT_LENGTH_BYTES ||
                iv.size != IV_LENGTH_BYTES ||
                ciphertext.size < MIN_CIPHERTEXT_LENGTH_BYTES
            ) {
                throw BackupCryptoException("Backup encryption details are invalid.")
            }

            val cipher = Cipher.getInstance(CIPHER_NAME)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, salt, iterations),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
            cipher.updateAAD(associatedData(iterations, saltBase64, ivBase64))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: BackupCryptoException) {
            throw error
        } catch (error: Exception) {
            throw BackupCryptoException("Wrong password or a corrupted backup.", error)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val passwordSpec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(KDF_NAME).generateSecret(passwordSpec).encoded
            try {
                SecretKeySpec(encoded, "AES")
            } finally {
                encoded.fill(0)
            }
        } finally {
            passwordSpec.clearPassword()
        }
    }

    private fun associatedData(iterations: Int, salt: String, iv: String): ByteArray {
        return "$FORMAT|$SCHEMA_VERSION|$KDF_NAME|$iterations|$salt|$CIPHER_NAME|$iv"
            .toByteArray(Charsets.UTF_8)
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray {
        return try {
            Base64.decode(this, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw BackupCryptoException("Backup encryption details are invalid.", error)
        }
    }
}

internal class BackupCryptoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
