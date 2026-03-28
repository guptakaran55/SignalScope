package com.signalscope.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.signalscope.app.data.*
import com.signalscope.app.util.TOTP
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Angel One SmartAPI client for Android.
 * Handles authentication (with TOTP), candle data fetching, and portfolio retrieval.
 */
class AngelOneClient(private val config: ConfigManager) {

    companion object {
        private const val TAG = "AngelOneClient"
        private const val BASE_URL = "https://apiconnect.angelone.in"
        private const val JSON_TYPE = "application/json"
        private const val RATE_LIMITED = "__RATE_LIMITED__"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var loginBackoff = 1000L // ms
    private var lastLoginAttempt = 0L

    // ═══════════════════════════════════════════════════════
    // AUTH
    // ═══════════════════════════════════════════════════════

    sealed class AuthResult {
        data class Success(val session: AngelSessionData) : AuthResult()
        data class Failure(val message: String) : AuthResult()
    }

    fun login(): AuthResult {
        if (!config.hasCredentials) {
            return AuthResult.Failure("Missing credentials. Configure in Settings.")
        }

        try {
            val totp = TOTP.generateTOTP(config.totpToken)
            Log.d(TAG, "Generated TOTP for login")

            val body = gson.toJson(mapOf(
                "clientcode" to config.clientId,
                "password" to config.password,
                "totp" to totp
            ))

            val request = Request.Builder()
                .url("$BASE_URL/rest/auth/angelbroking/user/v1/loginByPassword")
                .addHeader("Content-Type", JSON_TYPE)
                .addHeader("Accept", JSON_TYPE)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "127.0.0.1")
                .addHeader("X-ClientPublicIP", "127.0.0.1")
                .addHeader("X-MACAddress", "00:00:00:00:00:00")
                .addHeader("X-PrivateKey", config.apiKey)
                .post(body.toRequestBody(JSON_TYPE.toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (responseBody == null) {
                return AuthResult.Failure("Empty response from Angel One")
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val status = json.get("status")?.asBoolean ?: false

            if (!status) {
                val msg = json.get("message")?.asString ?: "Login failed"
                Log.e(TAG, "Login failed: $msg")
                return AuthResult.Failure(msg)
            }

            val data = json.getAsJsonObject("data")
            val jwtToken = data.get("jwtToken")?.asString ?: ""
            val refreshToken = data.get("refreshToken")?.asString ?: ""
            val feedToken = data.get("feedToken")?.asString

            if (jwtToken.isBlank()) {
                return AuthResult.Failure("No JWT token in response")
            }

            config.jwtToken = jwtToken
            config.refreshToken = refreshToken
            loginBackoff = 1000L

            Log.i(TAG, "Login successful")
            return AuthResult.Success(AngelSessionData(jwtToken, refreshToken, feedToken))

        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            return AuthResult.Failure("Login error: ${e.message}")
        }
    }

    fun ensureSession(): Boolean {
        if (config.jwtToken.isNotBlank()) {
            // Try a lightweight call to verify session
            try {
                val ok = verifySession()
                if (ok) return true
            } catch (e: Exception) {
                Log.d(TAG, "Session verification failed, re-logging in")
            }
        }

        // Rate limit login attempts
        val now = System.currentTimeMillis()
        if (now - lastLoginAttempt < loginBackoff) {
            return false
        }
        lastLoginAttempt = now

        return when (val result = login()) {
            is AuthResult.Success -> true
            is AuthResult.Failure -> {
                loginBackoff = (loginBackoff * 2).coerceAtMost(60000L)
                Log.w(TAG, "Login failed, backing off ${loginBackoff}ms: ${result.message}")
                false
            }
        }
    }

    private fun verifySession(): Boolean {
        val request = Request.Builder()
            .url("$BASE_URL/rest/secure/angelbroking/user/v1/getProfile")
            .addHeader("Authorization", "Bearer ${config.jwtToken}")
            .addHeader("Content-Type", JSON_TYPE)
            .addHeader("Accept", JSON_TYPE)
            .addHeader("X-UserType", "USER")
            .addHeader("X-SourceID", "WEB")
            .addHeader("X-ClientLocalIP", "127.0.0.1")
            .addHeader("X-ClientPublicIP", "127.0.0.1")
            .addHeader("X-MACAddress", "00:00:00:00:00:00")
            .addHeader("X-PrivateKey", config.apiKey)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return false
        val json = JsonParser.parseString(body).asJsonObject
        return json.get("status")?.asBoolean ?: false
    }

    // ═══════════════════════════════════════════════════════
    // CANDLE DATA
    // ═══════════════════════════════════════════════════════

    sealed class CandleResult {
        data class Success(val candles: List<CandleData>) : CandleResult()
        object NoData : CandleResult()
        object RateLimited : CandleResult()
        data class Error(val message: String) : CandleResult()
    }

    fun fetchCandleData(symbolToken: String, days: Int = 730): CandleResult {
        if (!ensureSession()) return CandleResult.RateLimited

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val cal = Calendar.getInstance()
            val toDate = sdf.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -days)
            val fromDate = sdf.format(cal.time)

            val body = gson.toJson(mapOf(
                "exchange" to "NSE",
                "symboltoken" to symbolToken,
                "interval" to "ONE_DAY",
                "fromdate" to fromDate,
                "todate" to toDate
            ))

            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/historical/v1/getCandleData")
                .addHeader("Authorization", "Bearer ${config.jwtToken}")
                .addHeader("Content-Type", JSON_TYPE)
                .addHeader("Accept", JSON_TYPE)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "127.0.0.1")
                .addHeader("X-ClientPublicIP", "127.0.0.1")
                .addHeader("X-MACAddress", "00:00:00:00:00:00")
                .addHeader("X-PrivateKey", config.apiKey)
                .post(body.toRequestBody(JSON_TYPE.toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return CandleResult.NoData

            val json = JsonParser.parseString(responseBody).asJsonObject
            val status = json.get("status")?.asBoolean ?: false

            if (!status) {
                val msg = json.get("message")?.asString ?: ""
                if (msg.lowercase().contains("rate") || msg.lowercase().contains("access")) {
                    return CandleResult.RateLimited
                }
                return CandleResult.NoData
            }

            val data = json.getAsJsonArray("data") ?: return CandleResult.NoData
            if (data.size() == 0) return CandleResult.NoData

            val candles = data.map { row ->
                val arr = row.asJsonArray
                CandleData(
                    timestamp = 0, // We don't need precise timestamps for analysis
                    open = arr[1].asDouble,
                    high = arr[2].asDouble,
                    low = arr[3].asDouble,
                    close = arr[4].asDouble,
                    volume = arr[5].asLong
                )
            }

            return CandleResult.Success(candles)

        } catch (e: IOException) {
            val msg = e.message?.lowercase() ?: ""
            if ("rate" in msg || "timeout" in msg) {
                return CandleResult.RateLimited
            }
            return CandleResult.Error(e.message ?: "Unknown error")
        } catch (e: Exception) {
            return CandleResult.Error(e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════════════════
    // PORTFOLIO
    // ═══════════════════════════════════════════════════════

    sealed class PortfolioResult {
        data class Success(val holdings: List<PortfolioHolding>) : PortfolioResult()
        data class Failure(val message: String) : PortfolioResult()
    }

    fun fetchPortfolio(): PortfolioResult {
        if (!ensureSession()) return PortfolioResult.Failure("Not authenticated")

        try {
            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/portfolio/v1/getHolding")
                .addHeader("Authorization", "Bearer ${config.jwtToken}")
                .addHeader("Content-Type", JSON_TYPE)
                .addHeader("Accept", JSON_TYPE)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "127.0.0.1")
                .addHeader("X-ClientPublicIP", "127.0.0.1")
                .addHeader("X-MACAddress", "00:00:00:00:00:00")
                .addHeader("X-PrivateKey", config.apiKey)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return PortfolioResult.Failure("Empty response")

            val json = JsonParser.parseString(responseBody).asJsonObject
            val status = json.get("status")?.asBoolean ?: false

            if (!status) {
                return PortfolioResult.Failure(json.get("message")?.asString ?: "Failed")
            }

            val data = json.getAsJsonArray("data") ?: return PortfolioResult.Success(emptyList())

            val holdings = data.map { item ->
                val obj = item.asJsonObject
                PortfolioHolding(
                    symbol = obj.get("tradingsymbol")?.asString ?: "",
                    token = obj.get("symboltoken")?.asString ?: "",
                    quantity = obj.get("quantity")?.asString?.toIntOrNull() ?: 0,
                    avgPrice = obj.get("averageprice")?.asString?.toDoubleOrNull() ?: 0.0,
                    ltp = obj.get("ltp")?.asString?.toDoubleOrNull() ?: 0.0,
                    pnl = obj.get("profitandloss")?.asString?.toDoubleOrNull() ?: 0.0
                )
            }.filter { it.quantity > 0 }

            return PortfolioResult.Success(holdings)

        } catch (e: Exception) {
            Log.e(TAG, "Portfolio fetch error", e)
            return PortfolioResult.Failure(e.message ?: "Unknown error")
        }
    }
}
