package com.signalscope.app.network

import android.util.Log
import com.signalscope.app.data.ConfigManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * AI-powered stock analysis client.
 *
 * Two features:
 * 1. Deep Pullback Analysis — auto-triggered when price drops >3% below EMA(21)
 *    Fetches Google News headlines → sends to LLM → returns probable cause
 *
 * 2. Stock Outlook — manually triggered from detail modal
 *    Fetches news + sends technical context → LLM returns short/long term outlook
 *
 * Supports OpenAI (ChatGPT) and Anthropic (Claude) APIs.
 */
object StockAiClient {

    private const val TAG = "StockAiClient"

    // MIUI/Xiaomi hardening — same recipe as Yahoo/Zerodha clients:
    // prefer IPv4 (flaky IPv6), HTTP/1.1 only (HTTP/2 is unstable on MIUI),
    // retry on connection failure, and a hard callTimeout so DNS hangs can't stall forever.
    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filter { it is java.net.Inet4Address }
            val v6 = all.filter { it !is java.net.Inet4Address }
            return v4 + v6
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)          // was missing — prompts can be large
        .callTimeout(40, TimeUnit.SECONDS)           // hard cap: spinner can't outlive this
        .retryOnConnectionFailure(true)
        .dns(ipv4PreferredDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // ═══════════════════════════════════════════════════════
    // GOOGLE NEWS — fetch recent headlines for a stock
    // ═══════════════════════════════════════════════════════

    fun fetchNewsHeadlines(symbol: String, maxResults: Int = 8): List<String> {
        // Clean symbol: remove .NS, .BO suffixes and -EQ
        val cleanSymbol = symbol
            .replace(".NS", "").replace(".BO", "")
            .replace("-EQ", "").replace("_", " ")

        val query = "$cleanSymbol stock NSE"
        val url = "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&hl=en-IN&gl=IN&ceid=IN:en"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseRssHeadlines(body, maxResults)
            }
        } catch (e: Exception) {
            Log.w(TAG, "News fetch failed for $symbol", e)
            emptyList()
        }
    }

    private fun parseRssHeadlines(xml: String, max: Int): List<String> {
        val headlines = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var currentTag = ""

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") inItem = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem && currentTag == "title" && parser.text.isNotBlank()) {
                            val title = parser.text.trim()
                            if (title.isNotEmpty() && !title.startsWith("<?")) {
                                headlines.add(title)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            inItem = false
                            if (headlines.size >= max) return headlines
                        }
                        currentTag = ""
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "RSS parse error", e)
        }
        return headlines
    }

    // ═══════════════════════════════════════════════════════
    // LLM CALL — supports OpenAI and Anthropic
    // ═══════════════════════════════════════════════════════

    sealed class AiResult {
        data class Success(val text: String) : AiResult()
        data class Error(val message: String) : AiResult()
    }

    private fun callLlm(config: ConfigManager, systemPrompt: String, userPrompt: String): AiResult {
        val apiKey = config.openaiApiKey
        if (apiKey.isBlank()) return AiResult.Error("No API key configured. Go to Settings → AI Stock Analysis.")

        return try {
            if (config.llmProvider == "anthropic") {
                callAnthropic(apiKey, config.llmModel, systemPrompt, userPrompt)
            } else {
                callOpenAi(apiKey, config.llmModel, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed", e)
            AiResult.Error("AI call failed: ${e.message}")
        }
    }

    private fun callOpenAi(apiKey: String, model: String, system: String, user: String): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return AiResult.Error("Empty response")
            if (!response.isSuccessful) {
                val errMsg = try { JSONObject(body).getJSONObject("error").getString("message") } catch (_: Exception) { body.take(200) }
                return AiResult.Error("OpenAI error: $errMsg")
            }
            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return AiResult.Success(content.trim())
        }
    }

    private fun callAnthropic(apiKey: String, model: String, system: String, user: String): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
            put("temperature", 0.3)
            put("system", system)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return AiResult.Error("Empty response")
            if (!response.isSuccessful) {
                val errMsg = try { JSONObject(body).getJSONObject("error").getString("message") } catch (_: Exception) { body.take(200) }
                return AiResult.Error("Anthropic error: $errMsg")
            }
            val content = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
            return AiResult.Success(content.trim())
        }
    }

    // ═══════════════════════════════════════════════════════
    // FEATURE 1: DEEP PULLBACK ANALYSIS
    // Auto-triggered when ema21PctDiff < -3%
    // ═══════════════════════════════════════════════════════

    fun analyzePullback(
        config: ConfigManager,
        symbol: String,
        price: Double,
        ema21PctDiff: Double,
        macdPhase: String,
        profitScore: Int,
        protectScore: Int
    ): AiResult {
        val headlines = fetchNewsHeadlines(symbol)
        if (headlines.isEmpty()) {
            return AiResult.Error("No recent news found for $symbol")
        }

        val system = """You are a stock market analyst. Analyze why a stock has pulled back significantly.
Be concise (3-4 sentences max). Focus on the most probable cause from the news.
Form your own independent opinion — do not assume any prior bullish or bearish bias.
End with a one-word classification: TEMPORARY, STRUCTURAL, or UNCERTAIN."""

        val user = buildString {
            append("Stock: $symbol (NSE India)\n")
            append("Current price: ₹${String.format("%.2f", price)}\n")
            append("Distance from EMA(21): ${String.format("%.1f", ema21PctDiff)}% (deep pullback)\n\n")
            // NOTE: profitScore / protectScore / macdPhase are deliberately NOT passed to the LLM
            // to avoid biasing the outlook with our own synthetic scores. The function signature
            // keeps them for backward compat with MainActivity's JS bridge wiring.
            append("Recent news headlines:\n")
            headlines.forEachIndexed { i, h -> append("${i + 1}. $h\n") }
            append("\nBased ONLY on the news above and publicly known facts about this company, ")
            append("what is the most probable reason for this pullback? Is it temporary (buy the dip) or structural (avoid)?")
        }

        return callLlm(config, system, user)
    }

    // ═══════════════════════════════════════════════════════
    // FEATURE 2: STOCK OUTLOOK (manually triggered)
    // User taps "Analyze Outlook" button in detail modal
    // ═══════════════════════════════════════════════════════

    fun analyzeOutlook(
        config: ConfigManager,
        symbol: String,
        price: Double,
        buyScore: Int,
        profitScore: Int,
        protectScore: Int,
        sellIntent: String,
        macdPhase: String,
        macdSlope: Double,
        rsi: Double?,
        sma200: Double?,
        ema21PctDiff: Double,
        rrRatio: Double,
        priceVel: Double
    ): AiResult {
        val headlines = fetchNewsHeadlines(symbol, 10)

        val system = """You are a stock market analyst providing outlook summaries for Indian NSE stocks.
Form your own independent opinion based on the news and publicly known fundamentals below.
Do NOT assume any prior bullish or bearish bias — you are given only neutral market data and headlines.

Give a structured response with:
1. SHORT TERM (1-4 weeks): Brief outlook based on standard technical indicators and recent news
2. LONG TERM (3-12 months): Outlook based on fundamentals from news and trend indicators
3. KEY RISKS: 1-2 bullet points
4. VERDICT: One of: STRONG BUY / BUY / HOLD / REDUCE / SELL

Keep each section to 2-3 sentences max. Be specific about price levels when possible."""

        val user = buildString {
            append("Stock: $symbol (NSE India)\n")
            append("Price: ₹${String.format("%.2f", price)}\n\n")
            append("── Standard Market Indicators ──\n")
            // Neutral, publicly-computable indicators only. No app-generated verdicts.
            if (rsi != null) append("RSI(14): ${String.format("%.1f", rsi)}\n")
            if (sma200 != null) append("SMA(200): ₹${String.format("%.2f", sma200)} — price is ${if (price > sma200) "above" else "below"}\n")
            append("EMA(21) distance: ${String.format("%.1f", ema21PctDiff)}%\n")
            append("MACD slope: ${String.format("%.3f", macdSlope)}\n")
            append("Daily velocity: ${String.format("%.2f", priceVel)}%\n\n")
            if (headlines.isNotEmpty()) {
                append("── Recent News ──\n")
                headlines.forEachIndexed { i, h -> append("${i + 1}. $h\n") }
            } else {
                append("(No recent news available — rely on general market knowledge of this company)\n")
            }
            append("\nBased ONLY on the data above and publicly known fundamentals, ")
            append("provide short-term and long-term outlook with verdict. ")
            append("Do not assume any prior app-generated scoring or recommendation.")
        }
        // NOTE: buyScore / profitScore / protectScore / sellIntent / rrRatio / macdPhase parameters
        // are deliberately NOT used in the prompt — they are app-generated synthetic verdicts that
        // would bias the LLM. The function signature keeps them for backward compat with the JS
        // bridge in MainActivity (Kotlin does not warn on unused function parameters).

        return callLlm(config, system, user)
    }
}
