package com.zenith.app.data.network

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted persistent session manager for Supabase Auth on Android.
 * Wraps serialized [UserSession] with Android Keystore AES-256-GCM before storing
 * in private [SharedPreferences], with transparent migration for legacy sessions
 * and safe fallback on non-Keystore JVM test harnesses.
 */
class SharedPreferencesSessionManager(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
) : SessionManager {

    private val tag = "ZenithSessionManager"
    private val prefs by lazy {
        context.getSharedPreferences("zenith_supabase_auth_session", Context.MODE_PRIVATE)
    }

    override suspend fun saveSession(session: UserSession) {
        withContext(Dispatchers.IO) {
            try {
                val serialized = json.encodeToString(UserSession.serializer(), session)
                val encryptedOrPlain = encryptSessionPayload(serialized)
                prefs.edit().putString(KEY_SESSION_DATA, encryptedOrPlain).apply()
            } catch (e: Exception) {
                Log.e(tag, "Failed to save Supabase session: ${e.message}")
            }
        }
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_SESSION_DATA, null) ?: return@withContext null
        try {
            val decrypted = decryptSessionPayload(raw) ?: return@withContext null
            val session = json.decodeFromString(UserSession.serializer(), decrypted)
            // Transparently upgrade legacy unencrypted JSON payloads when Keystore is available
            if (!raw.startsWith(ENC_PREFIX) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                encryptSessionPayload(decrypted).let { upgraded ->
                    if (upgraded.startsWith(ENC_PREFIX)) {
                        prefs.edit().putString(KEY_SESSION_DATA, upgraded).apply()
                    }
                }
            }
            session
        } catch (e: Exception) {
            Log.e(tag, "Failed to decode saved Supabase session: ${e.message}")
            null
        }
    }

    override suspend fun deleteSession() {
        withContext(Dispatchers.IO) {
            try {
                prefs.edit().remove(KEY_SESSION_DATA).apply()
            } catch (e: Exception) {
                Log.e(tag, "Failed to clear Supabase session: ${e.message}")
            }
        }
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            // Robolectric or unsupported hardware keystore falls back safely
            null
        }
    }

    private fun encryptSessionPayload(plaintext: String): String {
        val secretKey = getOrCreateKeystoreSecretKey() ?: return plaintext
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plaintext
        }
    }

    private fun decryptSessionPayload(stored: String): String? {
        if (!stored.startsWith(ENC_PREFIX)) return stored
        val secretKey = getOrCreateKeystoreSecretKey() ?: return null
        return try {
            val encoded = stored.removePrefix(ENC_PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val KEY_SESSION_DATA = "saved_supabase_session"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zenith_supabase_session_key_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENC_PREFIX = "enc_v1:"
        private const val GCM_IV_LENGTH = 12
    }
}
