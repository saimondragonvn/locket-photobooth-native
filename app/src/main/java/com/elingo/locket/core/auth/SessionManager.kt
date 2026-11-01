package com.elingo.locket.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.elingo.locket.core.crypto.OpenSslAesCipher
import com.elingo.locket.core.model.LocketSession
import com.elingo.locket.core.model.PerkTier
import com.elingo.locket.core.model.SavedAccount
import com.elingo.locket.core.model.StreakAnchor
import com.elingo.locket.core.model.SupporterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionManager(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "locket_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        context.getSharedPreferences("locket_fallback_session", Context.MODE_PRIVATE)
    }

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<LocketSession?> = _session.asStateFlow()

    private val _savedAccounts = MutableStateFlow(loadSavedAccounts())
    val savedAccounts: StateFlow<List<SavedAccount>> = _savedAccounts.asStateFlow()

    private val _supporterStatus = MutableStateFlow(loadSupporterStatus(_session.value?.localId))
    val supporterStatus: StateFlow<SupporterStatus> = _supporterStatus.asStateFlow()

    private val _themeId = MutableStateFlow(prefs.getString(KEY_THEME_ID, "gold") ?: "gold")
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    private val _isGlassStyle = MutableStateFlow(prefs.getBoolean(KEY_GLASS_STYLE, true))
    val isGlassStyle: StateFlow<Boolean> = _isGlassStyle.asStateFlow()

    fun currentSession(): LocketSession? = _session.value

    fun saveSession(
        newSession: LocketSession,
        encryptedCreds: OpenSslAesCipher.EncryptedCredentials? = null
    ) {
        prefs.edit().apply {
            putString(KEY_ACTIVE_SESSION, json.encodeToString(newSession))
            if (encryptedCreds != null) {
                putString(KEY_ENC_EMAIL, encryptedCreds.encryptedEmail)
                putString(KEY_ENC_PASSWORD, encryptedCreds.encryptedPassword)
            }
            apply()
        }
        _session.value = newSession
        _supporterStatus.value = loadSupporterStatus(newSession.localId)
        upsertSavedAccount(newSession, encryptedCreds)
    }

    fun getStoredEncryptedCredentials(): OpenSslAesCipher.EncryptedCredentials? {
        val encEmail = prefs.getString(KEY_ENC_EMAIL, null)
        val encPass = prefs.getString(KEY_ENC_PASSWORD, null)
        return if (!encEmail.isNullOrBlank() && !encPass.isNullOrBlank()) {
            OpenSslAesCipher.EncryptedCredentials(encEmail, encPass)
        } else {
            null
        }
    }

    fun updateProfileMetadata(
        displayName: String? = null,
        username: String? = null,
        photoUrl: String? = null
    ) {
        val current = _session.value ?: return
        val updated = current.copy(
            displayName = displayName ?: current.displayName,
            username = username ?: current.username,
            photoUrl = photoUrl ?: current.photoUrl
        )
        saveSession(updated, null)
    }

    fun switchToSavedAccount(uid: String): SavedAccount? {
        val target = _savedAccounts.value.firstOrNull { it.uid == uid } ?: return null
        val creds = if (!target.encryptedEmail.isNullOrBlank() && !target.encryptedPassword.isNullOrBlank()) {
            OpenSslAesCipher.EncryptedCredentials(target.encryptedEmail, target.encryptedPassword)
        } else {
            null
        }
        prefs.edit().apply {
            putString(KEY_ACTIVE_SESSION, json.encodeToString(target.session))
            if (creds != null) {
                putString(KEY_ENC_EMAIL, creds.encryptedEmail)
                putString(KEY_ENC_PASSWORD, creds.encryptedPassword)
            } else {
                remove(KEY_ENC_EMAIL)
                remove(KEY_ENC_PASSWORD)
            }
            apply()
        }
        _session.value = target.session
        _supporterStatus.value = loadSupporterStatus(target.uid)
        return target
    }

    fun removeSavedAccount(uid: String) {
        val filtered = _savedAccounts.value.filterNot { it.uid == uid }
        prefs.edit().putString(KEY_SAVED_ACCOUNTS, json.encodeToString(filtered)).apply()
        _savedAccounts.value = filtered
    }

    fun logoutCurrentSession() {
        prefs.edit()
            .remove(KEY_ACTIVE_SESSION)
            .remove(KEY_ENC_EMAIL)
            .remove(KEY_ENC_PASSWORD)
            .apply()
        _session.value = null
    }

    fun updateSupporterStatus(uid: String, totalVnd: Long, tiers: List<PerkTier>?) {
        val updated = SupporterStatus(
            totalDonatedVnd = totalVnd.coerceAtLeast(0L),
            tiers = tiers?.takeIf { it.isNotEmpty() } ?: _supporterStatus.value.tiers
        )
        prefs.edit()
            .putString("$KEY_SUPPORTER_PREFIX$uid", json.encodeToString(updated))
            .apply()
        if (_session.value?.localId == uid) {
            _supporterStatus.value = updated
        }
    }

    fun saveStreakAnchor(uid: String, anchor: StreakAnchor?, totalLockets: Int?) {
        prefs.edit().apply {
            if (anchor != null) {
                putString("$KEY_STREAK_PREFIX$uid", json.encodeToString(anchor))
            }
            if (totalLockets != null) {
                putInt("$KEY_LOCKETS_COUNT_PREFIX$uid", totalLockets)
            }
            apply()
        }
    }

    fun loadStreakAnchor(uid: String): Pair<StreakAnchor?, Int?> {
        val raw = prefs.getString("$KEY_STREAK_PREFIX$uid", null)
        val anchor = raw?.let { runCatching { json.decodeFromString<StreakAnchor>(it) }.getOrNull() }
        val lockets = if (prefs.contains("$KEY_LOCKETS_COUNT_PREFIX$uid")) {
            prefs.getInt("$KEY_LOCKETS_COUNT_PREFIX$uid", 0)
        } else {
            null
        }
        return anchor to lockets
    }

    fun setTheme(themeId: String) {
        prefs.edit().putString(KEY_THEME_ID, themeId).apply()
        _themeId.value = themeId
    }

    fun setGlassStyle(isGlass: Boolean) {
        prefs.edit().putBoolean(KEY_GLASS_STYLE, isGlass).apply()
        _isGlassStyle.value = isGlass
    }

    private fun upsertSavedAccount(
        session: LocketSession,
        creds: OpenSslAesCipher.EncryptedCredentials?
    ) {
        val currentList = _savedAccounts.value
        val existing = currentList.firstOrNull { it.uid == session.localId }
        val merged = SavedAccount(
            uid = session.localId,
            email = session.email.ifBlank { existing?.email.orEmpty() },
            name = session.displayName.ifBlank { existing?.name.orEmpty() },
            username = session.username.ifBlank { existing?.username.orEmpty() },
            avatar = session.photoUrl.ifBlank { existing?.avatar.orEmpty() },
            session = session,
            encryptedEmail = creds?.encryptedEmail ?: existing?.encryptedEmail,
            encryptedPassword = creds?.encryptedPassword ?: existing?.encryptedPassword
        )
        val nextList = if (existing != null) {
            currentList.map { if (it.uid == merged.uid) merged else it }
        } else {
            currentList + merged
        }
        prefs.edit().putString(KEY_SAVED_ACCOUNTS, json.encodeToString(nextList)).apply()
        _savedAccounts.value = nextList
    }

    private fun loadSession(): LocketSession? {
        val raw = prefs.getString(KEY_ACTIVE_SESSION, null) ?: return null
        return runCatching { json.decodeFromString<LocketSession>(raw) }.getOrNull()
    }

    private fun loadSavedAccounts(): List<SavedAccount> {
        val raw = prefs.getString(KEY_SAVED_ACCOUNTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedAccount>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadSupporterStatus(uid: String?): SupporterStatus {
        if (uid.isNullOrBlank()) return SupporterStatus()
        val raw = prefs.getString("$KEY_SUPPORTER_PREFIX$uid", null) ?: return SupporterStatus()
        return runCatching { json.decodeFromString<SupporterStatus>(raw) }.getOrDefault(SupporterStatus())
    }

    companion object {
        private const val KEY_ACTIVE_SESSION = "active_session_v1"
        private const val KEY_ENC_EMAIL = "enc_email_v1"
        private const val KEY_ENC_PASSWORD = "enc_password_v1"
        private const val KEY_SAVED_ACCOUNTS = "saved_accounts_v1"
        private const val KEY_SUPPORTER_PREFIX = "supporter_status_"
        private const val KEY_STREAK_PREFIX = "streak_anchor_"
        private const val KEY_LOCKETS_COUNT_PREFIX = "lockets_count_"
        private const val KEY_THEME_ID = "ui_theme_id"
        private const val KEY_GLASS_STYLE = "ui_glass_style"
    }
}

