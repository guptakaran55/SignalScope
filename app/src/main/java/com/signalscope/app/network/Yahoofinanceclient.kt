package com.signalscope.app.network


import android.util.Log
import com.signalscope.app.data.CandleData
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Yahoo Finance data fetcher — mirrors Python's us_market.py / yfinance integration.
 *
 * Used to fetch historical candle data for Zerodha holdings, since Zerodha's
 * Kite API does not provide historical OHLCV data for portfolio holdings.
 *
 * For Indian stocks, Yahoo Finance uses the format: SYMBOL.NS (NSE) or SYMBOL.BO (BSE)
 * Example: RELIANCE -> RELIANCE.NS
 *
 * Calls Yahoo Finance v8 chart API directly (no library dependency),
 * matching the Python fetch_index_chart_data() approach.
 */
object YahooFinanceClient {

    private const val TAG = "YahooFinanceClient"
    private const val BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)   // hard cap — prevents hanging on partial responses
        .build()

    sealed class CandleResult {
        data class Success(val candles: List<CandleData>) : CandleResult()
        object RateLimited : CandleResult()
        object NoData : CandleResult()
        data class Error(val message: String) : CandleResult()
    }

    /**
     * Fetch 2 years of daily candles for a stock.
     * For Indian stocks (NSE): appends ".NS" suffix automatically.
     * For US stocks (NASDAQ): uses symbol as-is.
     *
     * @param symbol  Trading symbol e.g. "RELIANCE" or "AAPL"
     * @param exchange "NSE", "BSE", or "NASDAQ" — determines Yahoo suffix
     */
    fun fetchCandles(symbol: String, exchange: String = "NSE"): CandleResult {
        val yahooSymbol = toYahooSymbol(symbol, exchange)

        return try {
            Log.d(TAG, "Fetching Yahoo Finance data for $yahooSymbol")

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/$yahooSymbol")
                .addQueryParameter("range", "2y")
                .addQueryParameter("interval", "1d")
                .addQueryParameter("includePrePost", "false")
                .addQueryParameter("events", "")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                )
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            response.use { resp ->
                when (resp.code) {
                    429 -> {
                        Log.w(TAG, "Yahoo Finance rate limited for $yahooSymbol")
                        return CandleResult.RateLimited
                    }
                    404 -> {
                        Log.w(TAG, "Symbol not found: $yahooSymbol")
                        return tryAlternateSuffix(symbol, exchange)
                    }
                    200 -> { /* continue */ }
                    else -> {
                        Log.w(TAG, "Yahoo Finance HTTP ${resp.code} for $yahooSymbol")
                        return CandleResult.NoData
                    }
                }

                val body = resp.body?.string() ?: return CandleResult.NoData
                parseYahooResponse(body, yahooSymbol)
            }

        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if ("rate" in msg || "429" in msg || "too many" in msg) {
                CandleResult.RateLimited
            } else {
                Log.e(TAG, "Yahoo Finance error for $yahooSymbol", e)
                CandleResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun tryAlternateSuffix(symbol: String, exchange: String): CandleResult {
        // If NSE didn't work, try BSE and vice versa
        val altSuffix = if (exchange == "NSE") ".BO" else ".NS"
        val altSymbol = "$symbol$altSuffix"

        return try {
            Log.d(TAG, "Trying alternate suffix: $altSymbol")

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/$altSymbol")
                .addQueryParameter("range", "2y")
                .addQueryParameter("interval", "1d")
                .addQueryParameter("includePrePost", "false")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                )
                .build()

            val response = client.newCall(request).execute()
            if (response.code != 200) return CandleResult.NoData

            val body = response.body?.string() ?: return CandleResult.NoData
            parseYahooResponse(body, altSymbol)

        } catch (e: Exception) {
            CandleResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Parse Yahoo Finance v8 chart API JSON response into CandleData list.
     * Mirrors Python's fetch_index_chart_data() parsing logic.
     */
    private fun parseYahooResponse(body: String, symbol: String): CandleResult {
        return try {
            val json = JSONObject(body)
            val chart = json.getJSONObject("chart")

            val resultArray = chart.optJSONArray("result") ?: return CandleResult.NoData
            if (resultArray.length() == 0) return CandleResult.NoData

            val result = resultArray.getJSONObject(0)
            val timestamps = result.getJSONArray("timestamp")
            val indicators = result.getJSONObject("indicators")
            val quoteArray = indicators.getJSONArray("quote")
            if (quoteArray.length() == 0) return CandleResult.NoData

            val quote = quoteArray.getJSONObject(0)
            val opens = quote.optJSONArray("open")
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val closes = quote.optJSONArray("close")
            val volumes = quote.optJSONArray("volume")

            if (closes == null || timestamps.length() < 10) return CandleResult.NoData

            val candles = mutableListOf<CandleData>()
            for (i in 0 until timestamps.length()) {
                val c = if (closes.isNull(i)) null else closes.optDouble(i)
                if (c == null || c.isNaN()) continue

                candles.add(
                    CandleData(
                        timestamp = timestamps.getLong(i),
                        open = if (opens == null || opens.isNull(i)) c else opens.optDouble(i, c),
                        high = if (highs == null || highs.isNull(i)) c else highs.optDouble(i, c),
                        low = if (lows == null || lows.isNull(i)) c else lows.optDouble(i, c),
                        close = c,
                        volume = if (volumes == null || volumes.isNull(i)) 0L else volumes.optLong(i, 0L)
                    )
                )
            }

            if (candles.size < 50) {
                Log.w(TAG, "Too few candles for $symbol: ${candles.size}")
                return CandleResult.NoData
            }

            Log.d(TAG, "Yahoo Finance: $symbol — ${candles.size} candles")
            CandleResult.Success(candles)

        } catch (e: Exception) {
            Log.e(TAG, "Yahoo Finance parse error for $symbol", e)
            CandleResult.Error("Parse error: ${e.message}")
        }
    }

    /**
     * Convert a trading symbol to Yahoo Finance format.
     * NSE stocks: RELIANCE -> RELIANCE.NS
     * BSE stocks: RELIANCE -> RELIANCE.BO
     * US stocks: AAPL -> AAPL (no suffix)
     *
     * Some Indian stocks have special characters that Yahoo handles differently.
     */
    fun toYahooSymbol(symbol: String, exchange: String = "NSE"): String {
        // Already has a suffix or is an index symbol (^NSEBANK, ^CNXIT, etc.) — return as-is
        if (symbol.contains(".") || symbol.startsWith("^")) return symbol

        return when (exchange.uppercase()) {
            "BSE" -> "$symbol.BO"
            "NASDAQ", "NYSE", "US" -> symbol
            else -> "$symbol.NS" // default to NSE
        }
    }
}