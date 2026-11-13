package dev.bookah.photos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionDataStore by preferencesDataStore("session")

/**
 * Keeps the session in DataStore with the app password encrypted by an Android Keystore key (AES-GCM).
 * The key never leaves the Keystore; if it is lost (a restore onto another phone) the session reads as empty and Chris signs in again.
 */
class KeystoreSessionStore(private val context: Context) : SessionStore {
    private val kServer = stringPreferencesKey("server")
    private val kUser = stringPreferencesKey("user")
    private val kSecret = stringPreferencesKey("secret")

    override suspend fun load(): Session? {
        val p = context.sessionDataStore.data.first()
        val server = p[kServer] ?: return null
        val user = p[kUser] ?: return null
        val secret = p[kSecret] ?: return null
        val password = runCatching { decrypt(secret) }.getOrNull() ?: return null
        return Session(server, user, password)
    }

    override suspend fun save(session: Session) {
        val encrypted = encrypt(session.appPassword)
        context.sessionDataStore.edit {
            it[kServer] = session.server
            it[kUser] = session.user
            it[kSecret] = encrypted
        }
    }

    override suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "bookahphotos-session" }
}
