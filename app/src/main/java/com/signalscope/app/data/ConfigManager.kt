package com.signalscope.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted credential storage for SignalScope.
 *
 * Stores credentials for:
 *   ZERODHA_API_KEY, ZERODHA_API_SECRET
 *   OPENAI_API_KEY (or Anthropic key) for AI stock analysis
 *
 * All sensitive values use EncryptedSharedPreferences (AES-256 GCM).
 * Non-sensitive config values use regular SharedPreferences.
 */
class ConfigManager(context: Context) {

    // ── Encrypted store (credentials) ──
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encrypted = EncryptedSharedPreferences.create(
        context,
        "signalscope_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
