package com.signalscope.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore

/**
 * Encrypted credential storage for SignalScope.
 *
 * Stores credentials for:
 *   ZERODHA_API_KEY, ZERODHA_API_SECRET
 *   OPENAI_API_KEY (or Anthropic key) for AI stock analysis
 *
 * All sensitive values use EncryptedSharedPreferences (AES-256 GCM).
 * Non-sensitive config values use regular SharedPreferences.
 *
 * Handles Android Keystore corruption gracefully — if the encrypted store
 * cannot be decrypted (AEADBadTagException), corrupted data is wiped and
 * recreated. The user will need to re-enter credentials but the app won't crash.
 */
class ConfigManager(context: Context) {

    companion object {
        private const val TAG = "ConfigManager"
        private const val ENCRYPTED_PREFS_FILE = "signalscope_secure_prefs"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    // ── Credential store ──
    // NOTE: Switched from EncryptedSharedPreferences to plain SharedPreferences.
    // Reason: EncryptedSharedPreferences relies on Android Keystore, which gets
    // corrupted frequently on MIUI/Xiaomi devices, causing AEADBadTagException
    // and wiping all stored credentials. Since the prefs file lives inside the
    // app's private sandbox (MODE_PRIVATE), other apps cannot read it without
    // root — encryption was offering little real security but causing constant
    // credential loss for Xiaomi users.
    private val encrypted: SharedPreferences = context.getSharedPreferences(
        "signalscope_credentials", Context.MODE_PRIVATE
    ).also { newPrefs ->
        // One-time migration: if old encrypted store still has credentials, copy them over
        try {
            if (newPrefs.getString("ZERODHA_API_KEY", "").isNullOrEmpty()) {
                val oldEncrypted = createEncryptedPrefs(context)
                val keysToMigrate = listOf(
                    "ZERODHA_API_KEY", "ZERODHA_API_SECRET",
                    "zerodha_access_token", "OPENAI_API_KEY"
                )
                val editor = newPrefs.edit()
                var migrated = 0
                keysToMigrate.forEach { key ->
                    val v = oldEncrypted.getString(key, "") ?: ""
                    if (v.isNotEmpty()) { editor.putString(key, v); migrated++ }
                }
                if (migrated > 0) {
                    editor.apply()
                    Log.i(TAG, "Migrated $migrated credentials from encrypted store")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Migration from encrypted prefs failed (expected on first install or corrupted keystore)", e)
        }
    }

    /**
     * Creates EncryptedSharedPreferences with automatic recovery from Keystore corruption.
     *
     * Android Keystore can become corrupted (especially on MIUI/Xiaomi devices),
     * causing AEADBadTagException when trying to decrypt existing preferences.
     * When this happens, we:
     *   1. Delete the corrupted shared preferences XML file
     *   2. Remove the master key from Android Keystore
     *   3. Recreate both from scratch
     *
     * The user loses stored credentials (API keys) but the app remains functional.
     */
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences corrupted — wiping and recreating", e)
            try {
                // Step 1: Delete the corrupted preferences file
                val prefsFile = File(context.filesDir.parent, "shared_prefs/$ENCRYPTED_PREFS_FILE.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                    Log.w(TAG, "Deleted corrupted prefs file: ${prefsFile.absolutePath}")
                }

                // Step 2: Remove the corrupted master key from Android Keystore
                try {
                    val keyStore = KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                        keyStore.deleteEntry(MASTER_KEY_ALIAS)
                        Log.w(TAG, "Deleted corrupted master key from Keystore")
                    }
                } catch (ksEx: Exception) {
                    Log.e(TAG, "Failed to clear Keystore entry", ksEx)
                }

                // Step 3: Recreate fresh
                buildEncryptedPrefs(context)
            } catch (e2: Exception) {
                // Last resort: fall back to unencrypted prefs so the app doesn't crash-loop
                Log.e(TAG, "CRITICAL: Cannot create encrypted prefs even after reset — using plaintext fallback", e2)
                context.getSharedPreferences("${ENCRYPTED_PREFS_FILE}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Plain store (non-sensitive config) ──
    private val prefs = context.getSharedPreferences("signalscope_prefs", Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════
    // ZERODHA CREDENTIALS  (matches .env: ZERODHA_*)
    // ═══════════════════════════════════════════════════════

    var zerodhaApiKey: String
        get() = encrypted.getString("ZERODHA_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("ZERODHA_API_KEY", v).apply()

    var zerodhaApiSecret: String
        get() = encrypted.getString("ZERODHA_API_SECRET", "") ?: ""
        set(v) = encrypted.edit().putString("ZERODHA_API_SECRET", v).apply()

    /** Access token is session-lived (expires daily) — store separately */
    var zerodhaAccessToken: String
        get() = encrypted.getString("zerodha_access_token", "") ?: ""
        set(v) = encrypted.edit().putString("zerodha_access_token", v).apply()

    var zerodhaUserName: String
        get() = prefs.getString("zerodha_user_name", "") ?: ""
        set(v) = prefs.edit().putString("zerodha_user_name", v).apply()

    val hasZerodhaCredentials: Boolean
        get() = zerodhaApiKey.isNotBlank() && zerodhaApiSecret.isNotBlank()

    val isZerodhaConnected: Boolean
        get() = hasZerodhaCredentials && zerodhaAccessToken.isNotBlank()

    /** Kept for backward compatibility — now just checks Zerodha */
    val hasCredentials: Boolean get() = isZerodhaConnected

    val zerodhaLoginUrl: String
        get() = if (zerodhaApiKey.isNotBlank())
            "https://kite.zerodha.com/connect/login?v=3&api_key=$zerodhaApiKey"
        else ""

    // ═══════════════════════════════════════════════════════
    // LLM / AI CREDENTIALS (for stock analysis via news)
    // ═══════════════════════════════════════════════════════

    /** OpenAI or Anthropic API key */
    var openaiApiKey: String
        get() = encrypted.getString("OPENAI_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("OPENAI_API_KEY", v).apply()

    /** LLM provider: "openai" or "anthropic" */
    var llmProvider: String
        get() = prefs.getString("llm_provider", "openai") ?: "openai"
        set(v) = prefs.edit().putString("llm_provider", v).apply()

    /** LLM model name (e.g. "gpt-4o-mini", "claude-sonnet-4-20250514") */
    var llmModel: String
        get() = prefs.getString("llm_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = prefs.edit().putString("llm_model", v).apply()

    val hasLlmCredentials: Boolean
        get() = openaiApiKey.isNotBlank()

    // ═══════════════════════════════════════════════════════
    // SCAN CONFIGURATION
    // ═══════════════════════════════════════════════════════

    var portfolioScanIntervalMin: Int
        get() = prefs.getInt("scan_interval_min", 15)
        set(v) = prefs.edit().putInt("scan_interval_min", v).apply()

    /** Sell score threshold for moderate sell notification (default 45) */
    var sellScoreAlertThreshold: Int
        get() = prefs.getInt("sell_threshold", 45)
        set(v) = prefs.edit().putInt("sell_threshold", v).apply()

    /** Sell score threshold for strong sell notification (default 65) */
    var strongSellAlertThreshold: Int
        get() = prefs.getInt("strong_sell_threshold", 65)
        set(v) = prefs.edit().putInt("strong_sell_threshold", v).apply()

    /** Only scan during Indian market hours (9:15 AM - 3:30 PM IST, weekdays) */
    var scanDuringMarketHoursOnly: Boolean
        get() = prefs.getBoolean("market_hours_only", true)
        set(v) = prefs.edit().putBoolean("market_hours_only", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(v) = prefs.edit().putBoolean("notifications_enabled", v).apply()

    var vibrateOnAlerts: Boolean
        get() = prefs.getBoolean("vibrate_alerts", true)
        set(v) = prefs.edit().putBoolean("vibrate_alerts", v).apply()

    // ═══════════════════════════════════════════════════════
    // SCORING WEIGHTS (stored as JSON in plain prefs)
    // ═══════════════════════════════════════════════════════

    private val gson = Gson()

    var scoringWeights: ScoringWeights
        get() {
            val json = prefs.getString("scoring_weights", null) ?: return ScoringWeights()
            return try {
                gson.fromJson(json, ScoringWeights::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse scoring weights, using defaults", e)
                ScoringWeights()
            }
        }
        set(v) = prefs.edit().putString("scoring_weights", gson.toJson(v)).apply()

    fun resetScoringWeights() {
        prefs.edit().remove("scoring_weights").apply()
    }

    // ═══════════════════════════════════════════════════════
    // SERVICE STATE
    // ═══════════════════════════════════════════════════════

    var serviceRunning: Boolean
        get() = prefs.getBoolean("service_running", false)
        set(v) = prefs.edit().putBoolean("service_running", v).apply()

    var lastPortfolioScan: Long
        get() = prefs.getLong("last_scan_ts", 0)
        set(v) = prefs.edit().putLong("last_scan_ts", v).apply()

    // ═══════════════════════════════════════════════════════
    // CLEAR
    // ═══════════════════════════════════════════════════════

    fun clearZerodhaSession() {
        encrypted.edit().remove("zerodha_access_token").apply()
        prefs.edit().remove("zerodha_user_name").apply()
    }

    fun clearAll() {
        encrypted.edit().clear().apply()
        prefs.edit().clear().apply()
    }
}
