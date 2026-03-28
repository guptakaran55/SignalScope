package com.signalscope.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted credential storage for SignalScope.
 *
 * Stores credentials matching the original Python .env file:
 *   ANGEL_API_KEY, ANGEL_CLIENT_ID, ANGEL_PASSWORD, ANGEL_TOTP_TOKEN
 *   ZERODHA_API_KEY, ZERODHA_API_SECRET
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
    // ANGEL ONE CREDENTIALS  (matches .env: ANGEL_*)
    // ═══════════════════════════════════════════════════════

    var apiKey: String
        get() = encrypted.getString("ANGEL_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("ANGEL_API_KEY", v).apply()

    var clientId: String
        get() = encrypted.getString("ANGEL_CLIENT_ID", "") ?: ""
        set(v) = encrypted.edit().putString("ANGEL_CLIENT_ID", v).apply()

    var password: String
        get() = encrypted.getString("ANGEL_PASSWORD", "") ?: ""
        set(v) = encrypted.edit().putString("ANGEL_PASSWORD", v).apply()

    var totpToken: String
        get() = encrypted.getString("ANGEL_TOTP_TOKEN", "") ?: ""
        set(v) = encrypted.edit().putString("ANGEL_TOTP_TOKEN", v).apply()

    // Session tokens (not credentials — refreshed on login)
    var jwtToken: String
        get() = encrypted.getString("angel_jwt", "") ?: ""
        set(v) = encrypted.edit().putString("angel_jwt", v).apply()

    var refreshToken: String
        get() = encrypted.getString("angel_refresh", "") ?: ""
        set(v) = encrypted.edit().putString("angel_refresh", v).apply()

    val hasAngelCredentials: Boolean
        get() = apiKey.isNotBlank() && clientId.isNotBlank() &&
                password.isNotBlank() && totpToken.isNotBlank()

    // Backwards-compat alias used in existing code
    val hasCredentials: Boolean get() = hasAngelCredentials

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

    val zerodhaLoginUrl: String
        get() = if (zerodhaApiKey.isNotBlank())
            "https://kite.zerodha.com/connect/login?v=3&api_key=$zerodhaApiKey"
        else ""

    // ═══════════════════════════════════════════════════════
    // SCAN CONFIGURATION
    // ═══════════════════════════════════════════════════════

    /** Which portfolio sources to scan: "angel", "zerodha", "both" */
    var portfolioSource: String
        get() = prefs.getString("portfolio_source", "both") ?: "both"
        set(v) = prefs.edit().putString("portfolio_source", v).apply()

    var portfolioScanIntervalMin: Int
        get() = prefs.getInt("scan_interval_min", 15)
        set(v) = prefs.edit().putInt("scan_interval_min", v).apply()

    /** Sell score threshold for moderate sell notification (default 45 — matches Python) */
    var sellScoreAlertThreshold: Int
        get() = prefs.getInt("sell_threshold", 45)
        set(v) = prefs.edit().putInt("sell_threshold", v).apply()

    /** Sell score threshold for strong sell notification (default 65 — matches Python) */
    var strongSellAlertThreshold: Int
        get() = prefs.getInt("strong_sell_threshold", 65)
        set(v) = prefs.edit().putInt("strong_sell_threshold", v).apply()

    /** Only scan during Indian market hours (9:15 AM – 3:30 PM IST, weekdays) */
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

    fun clearAngelCredentials() {
        encrypted.edit()
            .remove("ANGEL_API_KEY").remove("ANGEL_CLIENT_ID")
            .remove("ANGEL_PASSWORD").remove("ANGEL_TOTP_TOKEN")
            .remove("angel_jwt").remove("angel_refresh")
            .apply()
    }

    fun clearZerodhaSession() {
        encrypted.edit().remove("zerodha_access_token").apply()
        prefs.edit().remove("zerodha_user_name").apply()
    }

    fun clearAll() {
        encrypted.edit().clear().apply()
        prefs.edit().clear().apply()
    }
}