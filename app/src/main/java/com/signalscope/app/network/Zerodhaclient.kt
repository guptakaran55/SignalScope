package com.signalscope.app.network


import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.PortfolioHolding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Zerodha Kite API client.
 * Matches the Python app's zerodha integration exactly:
 *   - ZERODHA_API_KEY + ZERODHA_API_SECRET from credentials
 *   - Access token exchanged from request_token after login
 *   - Fetches holdings from /portfolio/holdings
 */
class ZerodhaClient(private val config: ConfigManager) {

    companion object {
        private const val TAG = "ZerodhaClient"
        private const val BASE_URL = "https://api.kite.trade"
        private const val JSON_TYPE = "application/json"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════
    // AUTH
    // ═══════════════════════════════════════════════════════

    sealed class ZerodhaAuthResult {
        data class Success(val accessToken: String, val userName: String) : ZerodhaAuthResult()
        data class Failure(val message: String) : ZerodhaAuthResult()
    }

    /**
     * Exchange a request_token (from Zerodha login redirect) for an access_token.
     * Mirrors Python: hashlib.sha256(api_key + request_token + api_secret).hexdigest()
     */
    fun exchangeRequestToken(requestToken: String): ZerodhaAuthResult {
        if (config.zerodhaApiKey.isBlank() || config.zerodhaApiSecret.isBlank()) {
            return ZerodhaAuthResult.Failure("Zerodha API key/secret not configured")
        }

        return try {
            // Compute SHA-256 checksum exactly as Python does
            val raw = config.zerodhaApiKey + requestToken + config.zerodhaApiSecret
            val checksum = sha256(raw)

            val formBody = FormBody.Builder()
                .add("api_key", config.zerodhaApiKey)
                .add("request_token", requestToken)
                .add("checksum", checksum)
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/session/token")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return ZerodhaAuthResult.Failure("Empty response")

            val json = JsonParser.parseString(body).asJsonObject
            if (json.get("status")?.asString != "success") {
                val msg = json.get("message")?.asString ?: "Unknown error"
                return ZerodhaAuthResult.Failure(msg)
            }

            val data = json.getAsJsonObject("data")
            val accessToken = data.get("access_token")?.asString ?: ""
            val userName = data.get("user_name")?.asString ?: ""

            config.zerodhaAccessToken = accessToken
            config.zerodhaUserName = userName

            Log.i(TAG, "Zerodha login successful: $userName")
            ZerodhaAuthResult.Success(accessToken, userName)

        } catch (e: Exception) {
            Log.e(TAG, "Zerodha token exchange error", e)
            ZerodhaAuthResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Set a pre-existing access token (e.g. pasted by user) and verify it.
     */
    fun setAndVerifyAccessToken(accessToken: String): ZerodhaAuthResult {
        config.zerodhaAccessToken = accessToken
        return verifyToken()
    }

    fun verifyToken(): ZerodhaAuthResult {
        if (config.zerodhaAccessToken.isBlank() || config.zerodhaApiKey.isBlank()) {
            return ZerodhaAuthResult.Failure("Not configured")
        }
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/user/profile")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return ZerodhaAuthResult.Failure("Empty response")

            val json = JsonParser.parseString(body).asJsonObject
            if (json.get("status")?.asString != "success") {
                config.zerodhaAccessToken = "" // clear invalid token
                return ZerodhaAuthResult.Failure("Token invalid or expired")
            }

            val userName = json.getAsJsonObject("data")?.get("user_name")?.asString ?: ""
            config.zerodhaUserName = userName
            ZerodhaAuthResult.Success(config.zerodhaAccessToken, userName)

        } catch (e: Exception) {
            ZerodhaAuthResult.Failure(e.message ?: "Unknown error")
        }
    }

    fun logout() {
        config.zerodhaAccessToken = ""
        config.zerodhaUserName = ""
    }

    val isConnected: Boolean
        get() = config.zerodhaAccessToken.isNotBlank() && config.zerodhaApiKey.isNotBlank()

    val loginUrl: String
        get() = if (config.zerodhaApiKey.isNotBlank())
            "https://kite.zerodha.com/connect/login?v=3&api_key=${config.zerodhaApiKey}"
        else ""

    // ═══════════════════════════════════════════════════════
    // HOLDINGS
    // ═══════════════════════════════════════════════════════

    sealed class HoldingsResult {
        data class Success(val holdings: List<PortfolioHolding>) : HoldingsResult()
        data class Expired(val message: String) : HoldingsResult()
        data class Failure(val message: String) : HoldingsResult()
    }

    /**
     * Fetch Zerodha portfolio holdings.
     * Returns holdings with quantity > 0, mapped to PortfolioHolding.
     * Mirrors Python's /api/zerodha/holdings endpoint logic.
     */
    fun fetchHoldings(): HoldingsResult {
        if (!isConnected) return HoldingsResult.Failure("Not connected to Zerodha")

        return try {
            val request = Request.Builder()
                .url("$BASE_URL/portfolio/holdings")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return HoldingsResult.Failure("Empty response")

            val json = JsonParser.parseString(body).asJsonObject
            if (json.get("status")?.asString != "success") {
                val msg = json.get("message")?.asString ?: "Unknown error"
                if (msg.lowercase().contains("token") || response.code == 403) {
                    config.zerodhaAccessToken = "" // clear expired token
                    return HoldingsResult.Expired("Zerodha session expired. Please reconnect.")
                }
                return HoldingsResult.Failure(msg)
            }

            val rawData = json.getAsJsonArray("data") ?: return HoldingsResult.Success(emptyList())

            val holdings = rawData.mapNotNull { item ->
                val obj = item.asJsonObject
                val qty = obj.get("quantity")?.asInt ?: 0
                if (qty <= 0) return@mapNotNull null

                val tradingSymbol = obj.get("tradingsymbol")?.asString ?: return@mapNotNull null
                val avgPrice = obj.get("average_price")?.asDouble ?: 0.0
                val ltp = obj.get("last_price")?.asDouble ?: 0.0
                val dayChange = obj.get("day_change")?.asDouble ?: 0.0
                val dayChangePct = obj.get("day_change_percentage")?.asDouble ?: 0.0
                val pnl = obj.get("pnl")?.asDouble ?: 0.0
                val exchange = obj.get("exchange")?.asString ?: "NSE"

                PortfolioHolding(
                    symbol = tradingSymbol,
                    token = "", // Zerodha doesn't use tokens the same way; Yahoo Finance uses symbol
                    quantity = qty,
                    avgPrice = avgPrice,
                    ltp = ltp,
                    pnl = pnl,
                    dayChange = dayChange,
                    dayChangePct = dayChangePct,
                    exchange = exchange,
                    source = "zerodha"
                )
            }

            Log.i(TAG, "Zerodha holdings fetched: ${holdings.size} stocks")
            HoldingsResult.Success(holdings)

        } catch (e: Exception) {
            Log.e(TAG, "Zerodha holdings fetch error", e)
            HoldingsResult.Failure(e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}