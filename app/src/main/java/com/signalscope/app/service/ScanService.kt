package com.signalscope.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.signalscope.app.data.*
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.ui.ScanServiceResultHolder
import com.signalscope.app.network.ZerodhaClient
import com.signalscope.app.ui.MainActivity
import com.signalscope.app.util.StockAnalyzer
import com.signalscope.app.util.ValueAnalyzer
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service with TWO scan modes:
 *
 * 1. PORTFOLIO MONITORING (automatic, market hours only)
 *    - Scans Zerodha holdings every 15 min
 *    - Sends push notifications on sell signals
 *    - Gated by market hours (9:15–15:30 IST weekdays)
 *
 * 2. DISCOVERY SCANNING (on-demand, anytime)
 *    - Scans full NIFTY 500 or NASDAQ 100 via Yahoo Finance
 *    - Shows buy scores, sell scores, all indicators
 *    - NOT gated by market hours — user triggers manually
 *    - No sell notifications (not held stocks)
 *    - Results stored in lastDiscoveryResult for UI display
 */
class ScanService : Service() {

    companion object {
        private const val TAG = "ScanService"
        const val CHANNEL_SCAN   = "signalscope_scan"
        const val CHANNEL_ALERTS = "signalscope_alerts"
        const val NOTIFICATION_SCAN_ID = 1

        // Portfolio monitoring actions
        const val ACTION_START    = "com.signalscope.START_SCAN"
        const val ACTION_STOP     = "com.signalscope.STOP_SCAN"
        const val ACTION_SCAN_NOW = "com.signalscope.SCAN_NOW"

        // Discovery scan actions (anytime, on-demand)
        const val ACTION_DISCOVERY_NIFTY500  = "com.signalscope.DISCOVERY_NIFTY500"
        const val ACTION_DISCOVERY_NASDAQ100 = "com.signalscope.DISCOVERY_NASDAQ100"
        const val ACTION_DISCOVERY_STOP      = "com.signalscope.DISCOVERY_STOP"

        // Broadcast events
        const val BROADCAST_SCAN_UPDATE      = "com.signalscope.SCAN_UPDATE"
        const val BROADCAST_DISCOVERY_UPDATE  = "com.signalscope.DISCOVERY_UPDATE"
        const val EXTRA_SCAN_STATUS           = "scan_status"
        const val EXTRA_DISCOVERY_PROGRESS    = "discovery_progress"
        const val EXTRA_DISCOVERY_TOTAL       = "discovery_total"
        const val EXTRA_DISCOVERY_MARKET      = "discovery_market"
        const val EXTRA_DISCOVERY_STATUS_TEXT = "discovery_status_text"

        fun createIntent(context: Context, action: String): Intent =
            Intent(context, ScanService::class.java).apply { this.action = action }
    }

    private lateinit var config: ConfigManager
    private lateinit var zerodhaClient: ZerodhaClient
    private lateinit var weights: ScoringWeights

    private var portfolioJob: Job? = null
    private var discoveryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Tiered scan frequency: full analysis every FULL_SCAN_INTERVAL scans,
    // quick scans (cached data) in between. Since daily indicators barely move
    // intraday, we only need full Yahoo API hits once per hour.
    private var scanCount = 0
    private val FULL_SCAN_EVERY_N = 4  // full scan every 4th cycle (= ~1 hour at 15-min intervals)

    // Alert deduplication: two strategies combined
    // - L4/L5 (urgent): time-based cooldown (can re-fire same day if conditions persist)
    // - L1/L2/L3 (slow-moving): once per trading day (daily indicators can't change intraday)
    private val recentAlerts = mutableMapOf<String, Long>()
    private val dailyAlertsSent = mutableSetOf<String>() // keys: "symbol_alertType_YYYY-MM-dd"
    private var lastTradingDate = "" // tracks date to reset dailyAlertsSent

    /** Returns true if this alert is urgent enough to use time-based cooldowns (vs once-per-day). */
    private fun isUrgentAlert(alertType: AlertType): Boolean = when (alertType) {
        AlertType.STRONG_EXIT -> true     // Both scores firing — act now
        AlertType.BOOK_PROFIT -> true     // MACD slope flipped — sell at peak
        else -> false
    }

    // Cooldown only applies to urgent alerts — the rest use once-per-day
    @Suppress("DEPRECATION")
    private fun alertCooldownMs(alertType: AlertType): Long = when (alertType) {
        AlertType.STRONG_EXIT         -> 15 * 60 * 1000L   // Both scores — act NOW
        AlertType.BOOK_PROFIT         -> 30 * 60 * 1000L   // MACD slope peak — sell for max profit
        AlertType.PROTECT_CAPITAL     -> 24 * 60 * 60 * 1000L  // Structural break — once per day
        AlertType.PEAK_WARNING        -> 24 * 60 * 60 * 1000L  // Prepare alert — once per day
        AlertType.GOLDEN_BUY          -> 24 * 60 * 60 * 1000L
        AlertType.STRONG_BUY          -> 24 * 60 * 60 * 1000L
        // Legacy types — fallback 24h
        else                          -> 24 * 60 * 60 * 1000L
    }

    private fun todayTradingDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return sdf.format(Date())
    }

    // ── Results accessible from UI ──
    var lastZerodhaResult: ScanResult? = null; private set
    var lastDiscoveryResult: DiscoveryScanResult? = null; private set

    // Discovery scan state
    @Volatile var isDiscoveryRunning = false; private set
    @Volatile var discoveryAbort = false

    override fun onCreate() {
        super.onCreate()
        config = ConfigManager(this)
        zerodhaClient = ZerodhaClient(config)
        weights = config.scoringWeights
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START              -> startPortfolioMonitoring()
            ACTION_STOP               -> stopAll()
            ACTION_SCAN_NOW           -> triggerImmediatePortfolioScan()
            ACTION_DISCOVERY_NIFTY500 -> startDiscoveryScan("nifty500")
            ACTION_DISCOVERY_NASDAQ100-> startDiscoveryScan("nasdaq100")
            ACTION_DISCOVERY_STOP     -> stopDiscoveryScan()
            else                      -> startPortfolioMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════
    // MODE 1: PORTFOLIO MONITORING (market hours only)
    // ═══════════════════════════════════════════════════════

    private fun startPortfolioMonitoring() {
        Log.i(TAG, "Starting portfolio monitoring")
        config.serviceRunning = true
        YahooFinanceClient.clearCache() // fresh start — first scan will do full 2y fetch

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalScope::ScanWakeLock").apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        startForeground(NOTIFICATION_SCAN_ID, buildScanNotification("Starting..."))

        portfolioJob?.cancel()
        portfolioJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (isMarketOpen()) {
                        runFullPortfolioScan()
                    } else {
                        // Outside market hours: portfolio monitoring pauses
                        // but service stays alive for on-demand discovery scans
                        val msg = if (isDiscoveryRunning)
                            "Discovery scan running..."
                        else
                            "Portfolio monitoring paused (market closed). Discovery scans available."
                        updateScanNotification(msg)
                    }
                    delay(config.portfolioScanIntervalMin * 60 * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Portfolio scan loop error", e)
                    updateScanNotification("Error: ${e.message}")
                    delay(60_000)
                }
            }
        }
    }

    private fun stopAll() {
        Log.i(TAG, "Stopping all scans")
        portfolioJob?.cancel()
        discoveryAbort = true
        discoveryJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        config.serviceRunning = false
        isDiscoveryRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun triggerImmediatePortfolioScan() {
        serviceScope.launch {
            try {
                // Immediate portfolio scan bypasses market hours check
                runFullPortfolioScan()
            } catch (e: Exception) {
                Log.e(TAG, "Immediate scan error", e)
                updateScanNotification("Scan failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // MODE 2: DISCOVERY SCANNING (anytime, on-demand)
    // Scans NIFTY 500 or NASDAQ 100 via Yahoo Finance
    // Shows full indicator data — no market hours gate
    // ═══════════════════════════════════════════════════════

    private fun startDiscoveryScan(market: String) {
        if (isDiscoveryRunning) {
            Log.w(TAG, "Discovery scan already running — ignoring")
            return
        }

        // Ensure service is in foreground (might be called when service isn't monitoring)
        if (!config.serviceRunning) {
            config.serviceRunning = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalScope::ScanWakeLock").apply {
                acquire(2 * 60 * 60 * 1000L) // 2 hours for a full scan
            }
            startForeground(NOTIFICATION_SCAN_ID, buildScanNotification("Starting discovery scan..."))
        }

        isDiscoveryRunning = true
        discoveryAbort = false
        ScanServiceResultHolder.isDiscoveryRunning = true
        ScanServiceResultHolder.discoveryMarket = if (market == "nifty500") "NIFTY 500" else "NASDAQ 100"

        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            try {
                if (market == "nifty500") {
                    runNifty500Discovery()
                } else {
                    runYahooDiscovery(NASDAQ_100_SYMBOLS, "NASDAQ 100", "$")
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Discovery scan error", e)
                val errMsg = "Discovery scan failed: ${e.message}"
                updateScanNotification(errMsg)
                ScanServiceResultHolder.discoveryStatusText = errMsg
            } finally {
                isDiscoveryRunning = false
                ScanServiceResultHolder.isDiscoveryRunning = false
                ScanServiceResultHolder.discoveryVersion++
            }
        }
    }

    private fun stopDiscoveryScan() {
        discoveryAbort = true
        discoveryJob?.cancel()
        isDiscoveryRunning = false
        ScanServiceResultHolder.isDiscoveryRunning = false
        ScanServiceResultHolder.discoveryStatusText = "Discovery scan stopped"
        ScanServiceResultHolder.discoveryVersion++
        updateScanNotification("Discovery scan stopped")
    }

    /**
     * Scan NIFTY 500 using Yahoo Finance for candle data.
     * Uses the full NIFTY_500_YAHOO_SYMBOLS list (~350+ stocks from NIFTY 50 +
     * NIFTY Next 50 + BSE 100 + Midcap 150), mirroring the Python fallback universe.
     */
    private suspend fun runNifty500Discovery() = withContext(Dispatchers.IO) {
        Log.i(TAG, "NIFTY 500 discovery — scanning ${NIFTY_500_YAHOO_SYMBOLS.size} stocks via Yahoo Finance")
        runYahooDiscovery(NIFTY_500_YAHOO_SYMBOLS, "NIFTY 500", "₹")
    }

    /**
     * Scan a list of symbols via Yahoo Finance.
     * Works for both NASDAQ 100 and Indian stocks (.NS suffix).
     * Results are streamed to lastDiscoveryResult as each stock completes.
     */
    /**
     * Scan a list of symbols via Yahoo Finance with adaptive pacing & retry queue.
     * Mirrors Python's run_nifty_scan() / run_nasdaq_scan() resilience logic:
     *   - Adaptive pace: starts at 0.5s, backs off 2x on rate limit, recovers 0.95x on success
     *   - Rate-limited stocks are queued for retry (not skipped)
     *   - After main pass, a retry phase re-scans failed stocks at gentler pace
     *   - Consecutive error cap triggers early exit to retry phase
     */
    private suspend fun runYahooDiscovery(
        symbols: List<String>,
        marketName: String,
        currency: String
    ) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val total = symbols.size
        val allStocks = mutableListOf<StockAnalysis>()
        var errors = 0
        var skipped = 0
        var lastError = ""

        // Adaptive pacing — mirrors Python's PACE_MIN / PACE_MAX / PACE_BACKOFF_MULT
        var currentPace = 500L        // start at 0.5s
        val PACE_MIN = 500L
        val PACE_MAX = 8000L
        val PACE_BACKOFF_MULT = 2.0
        val PACE_RECOVERY_MULT = 0.95
        var consecutiveErrors = 0
        val retryQueue = mutableListOf<String>()

        val startText = "Discovery: $marketName — 0/$total scanned"
        updateScanNotification(startText)
        ScanServiceResultHolder.discoveryProgress = 0
        ScanServiceResultHolder.discoveryTotal = total
        ScanServiceResultHolder.discoveryStatusText = startText
        ScanServiceResultHolder.discoveryVersion++
        Log.i(TAG, "Discovery scan started: $marketName — $total symbols")

        // ── Main scan pass ──
        for ((idx, symbol) in symbols.withIndex()) {
            if (discoveryAbort) {
                Log.i(TAG, "Discovery scan aborted at $idx/$total")
                break
            }

            // If too many consecutive errors, bail to retry phase
            // (raised 30 → 50: the old threshold tripped too easily during Yahoo rate-limit spikes,
            //  aborting the main pass with ~210 stocks unscanned — retry phase then often couldn't
            //  catch up before hitting its own 20-error cap.)
            if (consecutiveErrors >= 50) {
                Log.w(TAG, "Discovery: $consecutiveErrors consecutive errors — queuing ${symbols.size - idx} remaining for retry phase")
                retryQueue.addAll(symbols.subList(idx, symbols.size))
                break
            }

            try {
                val exchange = if (currency == "₹") "NSE" else "NASDAQ"
                val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        consecutiveErrors = 0
                        var analysis = StockAnalyzer.analyze(
                            candles = candleResult.candles,
                            symbol = symbol,
                            name = symbol,
                            token = symbol,
                            minAvgVolume = if (currency == "₹") 100000 else 50000,
                            w = weights
                        )
                        if (analysis != null) {
                            // Fetch fundamentals for value analysis
                            try {
                                Log.d(TAG, "Fetching fundamentals for $symbol (exchange=$exchange)")
                                val fundResult = YahooFinanceClient.fetchFundamentals(symbol, exchange)
                                Log.d(TAG, "Fundamentals result for $symbol: ${fundResult::class.simpleName}")
                                when (fundResult) {
                                    is YahooFinanceClient.FundamentalResult.Success -> {
                                        val vr = ValueAnalyzer.analyze(
                                            fundamentals = fundResult.data,
                                            currentPrice = analysis.price,
                                            sectorMedianPe = null,
                                            w = weights
                                        )
                                        Log.d(TAG, "Value score for $symbol: ${vr.valueScore} (${vr.valueRating}) PE=${vr.trailingPe} PB=${vr.priceToBook}")
                                        analysis = analysis.copy(
                                            trailingPe = vr.trailingPe,
                                            priceToBook = vr.priceToBook,
                                            evToEbitda = vr.evToEbitda,
                                            debtToEquity = vr.debtToEquity,
                                            roce = vr.roce,
                                            dividendYield = vr.dividendYield,
                                            operatingCashflow = vr.operatingCashflow,
                                            netIncome = vr.netIncome,
                                            fiftyTwoWeekLow = vr.fiftyTwoWeekLow,
                                            fiftyTwoWeekHigh = vr.fiftyTwoWeekHigh,
                                            sharesOutstanding = vr.sharesOutstanding,
                                            hasBuyback = vr.hasBuyback,
                                            valueScore = vr.valueScore,
                                            valueRating = vr.valueRating
                                        )
                                    }
                                    is YahooFinanceClient.FundamentalResult.RateLimited ->
                                        Log.w(TAG, "Fundamentals rate limited for $symbol — skipping value data")
                                    is YahooFinanceClient.FundamentalResult.NoData ->
                                        Log.w(TAG, "No fundamental data for $symbol")
                                    is YahooFinanceClient.FundamentalResult.Error ->
                                        Log.w(TAG, "Fundamentals error for $symbol: ${fundResult.message}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Fundamentals fetch failed for $symbol — technical data kept", e)
                            }
                            allStocks.add(analysis)
                        } else {
                            skipped++
                        }
                        // Recover pace on success
                        currentPace = (currentPace * PACE_RECOVERY_MULT).toLong().coerceAtLeast(PACE_MIN)
                    }
                    is YahooFinanceClient.CandleResult.RateLimited -> {
                        consecutiveErrors++
                        errors++
                        lastError = "Rate limited on $symbol — backing off ${currentPace}ms"
                        retryQueue.add(symbol) // queue for retry instead of skipping
                        currentPace = (currentPace * PACE_BACKOFF_MULT).toLong().coerceAtMost(PACE_MAX)
                        Log.w(TAG, "Discovery: rate limited on $symbol, pace=${currentPace}ms, queued for retry")
                        // Extra backoff wait on rate limit
                        val backoffWait = (3000L + consecutiveErrors * 500L).coerceAtMost(15000L)
                        delay(backoffWait)
                    }
                    is YahooFinanceClient.CandleResult.NoData -> {
                        consecutiveErrors = 0
                        skipped++
                    }
                    is YahooFinanceClient.CandleResult.Error -> {
                        consecutiveErrors++
                        errors++
                        lastError = "$symbol: ${candleResult.message}"
                        Log.w(TAG, "Discovery: error for $symbol — ${candleResult.message}")
                    }
                }

                // Update progress every stock (not just every 5 — gives smoother UI updates)
                val sorted = allStocks.sortedByDescending { it.buyScore }
                lastDiscoveryResult = DiscoveryScanResult(
                    market = marketName,
                    currency = currency,
                    timestamp = System.currentTimeMillis(),
                    totalScanned = allStocks.size,
                    totalSymbols = total,
                    skipped = skipped,
                    errors = errors,
                    lastError = lastError,
                    isComplete = false,
                    allStocks = sorted,
                    buySignals = sorted.filter { it.buyScore >= 60 },
                    strongBuys = sorted.filter { it.buyScore >= 75 },
                    goldenBuys = sorted.filter { it.goldenBuy },
                    setups = sorted.filter {
                        (it.sma200 != null && it.price > it.sma200) &&
                                (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
                    },
                    durationMs = System.currentTimeMillis() - startTime
                )
                ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
                ScanServiceResultHolder.discoveryProgress = idx + 1
                ScanServiceResultHolder.discoveryTotal = total
                // Honest status: attempted / total, with a breakdown of outcomes so the user
                // understands why "analyzed" is lower than "attempted" (filtered, skipped, errored).
                val analyzed = allStocks.size
                val breakdown = "$analyzed ok · $skipped skip · $errors err"
                ScanServiceResultHolder.discoveryStatusText =
                    "Discovery $marketName: ${idx + 1}/$total — $breakdown"
                ScanServiceResultHolder.discoveryVersion++

                updateScanNotification(ScanServiceResultHolder.discoveryStatusText)

                // Periodic disk save every 25 stocks — survives mid-scan process death (MIUI)
                if ((idx + 1) % 25 == 0 && allStocks.isNotEmpty()) {
                    try {
                        DiscoveryResultStore.save(this@ScanService, lastDiscoveryResult!!)
                    } catch (e: Exception) {
                        Log.w(TAG, "Periodic save failed", e)
                    }
                }

                // Pacing delay (only once — not double-delayed on rate limits)
                delay(currentPace)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                errors++
                lastError = "$symbol: ${e.message}"
                Log.e(TAG, "Discovery error for $symbol", e)
            }
        }

        // ── Retry phase — re-scan rate-limited stocks at gentler pace ──
        if (retryQueue.isNotEmpty() && !discoveryAbort) {
            // 30s cool-down (was 10s) — gives Yahoo's rate limiter more time to reset.
            // The old 10s often wasn't enough: retry phase would start still rate-limited and
            // burn through its 20-error cap within the first few seconds.
            Log.i(TAG, "Discovery retry phase: ${retryQueue.size} stocks, cooling down 30s...")
            updateScanNotification("Discovery $marketName: cooling down 30s before retrying ${retryQueue.size}...")
            delay(30_000L)

            val retryPace = 2000L // 2s between retries (was 1.5s) — gentler on rate limiter
            var retryErrors = 0

            for ((rIdx, symbol) in retryQueue.withIndex()) {
                // raised 20 → 40: rate-limit flutter during retry shouldn't abort prematurely
                if (discoveryAbort || retryErrors >= 40) {
                    if (retryErrors >= 40) Log.w(TAG, "Discovery retry: hit $retryErrors errors — giving up on remaining ${retryQueue.size - rIdx}")
                    break
                }

                try {
                    val exchange = if (currency == "₹") "NSE" else "NASDAQ"
                    val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                    when (candleResult) {
                        is YahooFinanceClient.CandleResult.Success -> {
                            var analysis = StockAnalyzer.analyze(
                                candles = candleResult.candles,
                                symbol = symbol,
                                name = symbol,
                                token = symbol,
                                minAvgVolume = if (currency == "₹") 100000 else 50000,
                                w = weights
                            )
                            if (analysis != null) {
                                try {
                                    val fundResult = YahooFinanceClient.fetchFundamentals(symbol, exchange)
                                    if (fundResult is YahooFinanceClient.FundamentalResult.Success) {
                                        val vr = ValueAnalyzer.analyze(fundResult.data, analysis.price, w = weights)
                                        analysis = analysis.copy(
                                            trailingPe = vr.trailingPe, priceToBook = vr.priceToBook,
                                            evToEbitda = vr.evToEbitda, debtToEquity = vr.debtToEquity,
                                            roce = vr.roce, dividendYield = vr.dividendYield,
                                            operatingCashflow = vr.operatingCashflow, netIncome = vr.netIncome,
                                            fiftyTwoWeekLow = vr.fiftyTwoWeekLow, fiftyTwoWeekHigh = vr.fiftyTwoWeekHigh,
                                            sharesOutstanding = vr.sharesOutstanding, hasBuyback = vr.hasBuyback,
                                            valueScore = vr.valueScore, valueRating = vr.valueRating
                                        )
                                    }
                                } catch (_: Exception) {}
                                allStocks.add(analysis)
                            } else {
                                skipped++
                            }
                        }
                        is YahooFinanceClient.CandleResult.RateLimited -> {
                            retryErrors++
                            errors++
                            delay(5000L) // extra 5s cool-down on retry rate limit
                        }
                        is YahooFinanceClient.CandleResult.NoData -> skipped++
                        is YahooFinanceClient.CandleResult.Error -> {
                            retryErrors++
                            errors++
                        }
                    }

                    // Update UI during retry
                    val sorted = allStocks.sortedByDescending { it.buyScore }
                    lastDiscoveryResult = DiscoveryScanResult(
                        market = marketName, currency = currency,
                        timestamp = System.currentTimeMillis(),
                        totalScanned = allStocks.size, totalSymbols = total,
                        skipped = skipped, errors = errors, lastError = lastError, isComplete = false,
                        allStocks = sorted,
                        buySignals = sorted.filter { it.buyScore >= 60 },
                        strongBuys = sorted.filter { it.buyScore >= 75 },
                        goldenBuys = sorted.filter { it.goldenBuy },
                        setups = sorted.filter {
                            (it.sma200 != null && it.price > it.sma200) &&
                                    (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
                        },
                        durationMs = System.currentTimeMillis() - startTime
                    )
                    ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
                    ScanServiceResultHolder.discoveryProgress = total - retryQueue.size + rIdx + 1
                    // Retry status — show it's the retry phase so user doesn't think main scan is stuck
                    val retryAnalyzed = allStocks.size
                    ScanServiceResultHolder.discoveryStatusText =
                        "Discovery $marketName — RETRY ${rIdx + 1}/${retryQueue.size} · $retryAnalyzed total ok · $retryErrors err"
                    ScanServiceResultHolder.discoveryVersion++

                    updateScanNotification(ScanServiceResultHolder.discoveryStatusText)

                    delay(retryPace)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryErrors++
                    errors++
                    Log.e(TAG, "Discovery retry error for $symbol", e)
                }
            }
        }

        // ── Final result ──
        val sorted = allStocks.sortedByDescending { it.buyScore }
        val elapsed = System.currentTimeMillis() - startTime
        lastDiscoveryResult = DiscoveryScanResult(
            market = marketName,
            currency = currency,
            timestamp = System.currentTimeMillis(),
            totalScanned = allStocks.size,
            totalSymbols = total,
            skipped = skipped,
            errors = errors,
            lastError = lastError,
            isComplete = !discoveryAbort,
            allStocks = sorted,
            buySignals = sorted.filter { it.buyScore >= 60 },
            strongBuys = sorted.filter { it.buyScore >= 75 },
            goldenBuys = sorted.filter { it.goldenBuy },
            setups = sorted.filter {
                (it.sma200 != null && it.price > it.sma200) &&
                        (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
            },
            durationMs = elapsed
        )
        ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
        ScanServiceResultHolder.discoveryVersion++

        // Persist to disk so results survive process death
        DiscoveryResultStore.save(this@ScanService, lastDiscoveryResult!!)

        val statusMsg = if (discoveryAbort) "Discovery stopped" else
            "Discovery done: ${allStocks.size} stocks in ${elapsed / 60000}m"
        updateScanNotification(statusMsg)
        ScanServiceResultHolder.discoveryStatusText = statusMsg
        Log.i(TAG, "$statusMsg — ${sorted.count { it.buyScore >= 60 }} buy signals, " +
                "${sorted.count { it.goldenBuy }} golden buys" +
                if (retryQueue.isNotEmpty()) " (retried ${retryQueue.size})" else "")
    }

    // ═══════════════════════════════════════════════════════
    // FULL PORTFOLIO SCAN  (Zerodha only)
    // ═══════════════════════════════════════════════════════

    private suspend fun runFullPortfolioScan() {
        if (!config.isZerodhaConnected) {
            updateScanNotification("Zerodha not connected — open Settings")
            broadcastUpdate()
            return
        }

        scanCount++
        val isFullScan = scanCount % FULL_SCAN_EVERY_N == 1 || scanCount == 1
        val scanType = if (isFullScan) "Full scan" else "Quick scan"
        updateScanNotification("$scanType: Zerodha portfolio...")

        val result = if (isFullScan) {
            // Full scan: fetch candles (cached 2y + 5d refresh), recompute all indicators
            scanZerodhaPortfolio()
        } else {
            // Quick scan: re-fetch holdings for updated LTP, reuse cached candle data
            // The fetchCandlesCached call is extremely cheap here — it only hits Yahoo
            // for 5 days of data (or returns pure cache if recently refreshed)
            scanZerodhaPortfolio()
        }

        lastZerodhaResult = result
        ScanServiceResultHolder.lastZerodhaResult = result
        ScanServiceResultHolder.portfolioVersion++

        if (isFullScan) {
            // Full scan: process all alert types
            processAlerts(result.alerts)
        } else {
            // Quick scan: only process urgent alerts (L4/L5) — L1-L3 already sent once today
            processAlerts(result.alerts.filter { isUrgentAlert(it.alertType) })
        }

        config.lastPortfolioScan = System.currentTimeMillis()

        val totalAlerts = result.alerts.size
        val statusText = when {
            totalAlerts > 0 -> "⚠ $totalAlerts alert(s) — tap to view"
            else -> "✓ Portfolio OK — no sell signals"
        }
        updateScanNotification(statusText)
        broadcastUpdate()
    }

    private suspend fun scanZerodhaPortfolio(): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val alerts = mutableListOf<StockAlert>()
        val scannedHoldings = mutableListOf<PortfolioHolding>()

        val holdingsResult = zerodhaClient.fetchHoldings()
        val rawHoldings = when (holdingsResult) {
            is ZerodhaClient.HoldingsResult.Success -> holdingsResult.holdings
            is ZerodhaClient.HoldingsResult.Expired -> {
                config.zerodhaAccessToken = ""
                sendZerodhaExpiredNotification()
                return@withContext emptyScanResult(startTime)
            }
            is ZerodhaClient.HoldingsResult.Failure -> {
                Log.e(TAG, "Zerodha holdings failed: ${holdingsResult.message}")
                return@withContext emptyScanResult(startTime)
            }
        }

        for (holding in rawHoldings) {
            try {
                // Use cached candles for portfolio scans — avoids re-fetching 2y of
                // daily data every 15 min. Only today's candle gets refreshed.
                val candleResult = YahooFinanceClient.fetchCandlesCached(holding.symbol, holding.exchange)
                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        val analysis = StockAnalyzer.analyze(
                            candles = candleResult.candles,
                            symbol = holding.symbol, name = holding.symbol,
                            token = holding.symbol, minAvgVolume = 0,
                            w = weights
                        )
                        val verdict = computeVerdict(analysis, holding.totalReturnPct)
                        scannedHoldings.add(holding.copy(analysis = analysis, verdict = verdict))
                        if (analysis != null) {
                            generateAlerts(holding, analysis, holding.totalReturnPct)
                                ?.let { alerts.addAll(it) }
                        }
                        delay(600)
                    }
                    is YahooFinanceClient.CandleResult.RateLimited -> {
                        delay(10_000)
                    }
                    else -> scannedHoldings.add(holding.copy(verdict = "NO DATA"))
                }
            } catch (_: Exception) {
                scannedHoldings.add(holding.copy(verdict = "ERROR"))
            }
        }
        buildScanResult(startTime, scannedHoldings, alerts)
    }

    // ═══════════════════════════════════════════════════════
    // MARKET HOURS CHECK (portfolio monitoring only)
    // ═══════════════════════════════════════════════════════

    private fun isMarketOpen(): Boolean {
        if (!config.scanDuringMarketHoursOnly) return true

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val totalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
        return totalMin in (9 * 60)..(16 * 60) // 9:00 AM to 4:00 PM IST
    }

    // ═══════════════════════════════════════════════════════
    // VERDICT + ALERTS (unchanged from your code)
    // ═══════════════════════════════════════════════════════

    /** Verdict now uses the intent label from the dual scoring system. */
    private fun computeVerdict(analysis: StockAnalysis?, returnPct: Double): String {
        if (analysis == null) return "HOLD"
        return analysis.sellIntent  // "STRONG EXIT", "BOOK PROFIT", "PROTECT CAPITAL", or "HOLD"
    }

    /**
     * Generate alerts from the dual scoring system.
     * - Intent-based: STRONG_EXIT, BOOK_PROFIT, PROTECT_CAPITAL (from sellIntent)
     * - Stage 1 warning: PEAK_WARNING (earlySell / EARLY SELL phase — prepare to sell)
     */
    private fun generateAlerts(
        holding: PortfolioHolding, analysis: StockAnalysis,
        returnPct: Double
    ): List<StockAlert>? {
        val source = "zerodha"
        val alerts = mutableListOf<StockAlert>()

        // Intent-based alerts from dual scoring
        when (analysis.sellIntent) {
            "STRONG EXIT" -> alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.STRONG_EXIT,
                message = "Profit ${analysis.profitScore}/58 + Protect ${analysis.protectScore}/58 — sell all",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))

            "BOOK PROFIT" -> alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.BOOK_PROFIT,
                message = "Profit Booking ${analysis.profitScore}/58 — MACD slope reversed, sell 50-70%",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))

            "PROTECT CAPITAL" -> alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.PROTECT_CAPITAL,
                message = "Capital Protection ${analysis.protectScore}/58 — trend broken, exit position",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }

        // Stage 1 warning: MACD decelerating (EARLY SELL phase) — prepare to sell
        // Only fire if no stronger sell intent already generated
        if (analysis.macdPhase == "EARLY SELL" && analysis.sellIntent == "HOLD") {
            alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.PEAK_WARNING,
                message = "MACD decelerating — peak may be approaching, prepare to sell",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }

        return if (alerts.isNotEmpty()) alerts else null
    }

    private fun processAlerts(alerts: List<StockAlert>) {
        if (!config.notificationsEnabled) return
        val now = System.currentTimeMillis()
        val today = todayTradingDate()

        // Reset daily alert set on new trading day
        if (today != lastTradingDate) {
            dailyAlertsSent.clear()
            lastTradingDate = today
        }

        // Periodic cleanup: remove entries older than 24 hours to prevent unbounded growth
        if (recentAlerts.size > 100) {
            val dayAgo = now - 24 * 60 * 60 * 1000L
            recentAlerts.entries.removeAll { it.value < dayAgo }
        }

        for (alert in alerts) {
            if (isUrgentAlert(alert.alertType)) {
                // L4/L5: time-based cooldown — can re-fire if condition persists
                val key = "${alert.symbol}_${alert.alertType}_${alert.source}"
                val lastAlerted = recentAlerts[key] ?: 0L
                if (now - lastAlerted > alertCooldownMs(alert.alertType)) {
                    sendAlertNotification(alert)
                    recentAlerts[key] = now
                }
            } else {
                // L1/L2/L3: once per trading day — daily indicators don't change intraday
                val dailyKey = "${alert.symbol}_${alert.alertType}_$today"
                if (dailyKey !in dailyAlertsSent) {
                    sendAlertNotification(alert)
                    dailyAlertsSent.add(dailyKey)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SCAN, "Scan Status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background scan progress"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ALERTS, "Stock Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Sell signals and portfolio alerts"; enableVibration(config.vibrateOnAlerts); setShowBadge(true) })
    }

    private fun buildScanNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_SCAN)
            .setContentTitle("SignalScope")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateScanNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_SCAN_ID, buildScanNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun sendAlertNotification(alert: StockAlert) {
        val title = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> "🔴 STRONG EXIT: ${alert.symbol}"
            AlertType.BOOK_PROFIT     -> "💰 BOOK PROFIT: ${alert.symbol}"
            AlertType.PROTECT_CAPITAL -> "🛡️ PROTECT CAPITAL: ${alert.symbol}"
            AlertType.PEAK_WARNING    -> "⚡ Peak Approaching: ${alert.symbol}"
            AlertType.GOLDEN_BUY      -> "⭐ Golden Buy: ${alert.symbol}"
            AlertType.STRONG_BUY      -> "🟢 Buy: ${alert.symbol}"
            else -> "📊 ${alert.alertType}: ${alert.symbol}"
        }
        val priority = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> NotificationCompat.PRIORITY_MAX
            AlertType.BOOK_PROFIT     -> NotificationCompat.PRIORITY_HIGH
            AlertType.PROTECT_CAPITAL -> NotificationCompat.PRIORITY_HIGH
            AlertType.PEAK_WARNING    -> NotificationCompat.PRIORITY_DEFAULT
            AlertType.GOLDEN_BUY      -> NotificationCompat.PRIORITY_DEFAULT
            AlertType.STRONG_BUY      -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }
        val alertColor = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> 0xFFDC2626.toInt()  // Red
            AlertType.BOOK_PROFIT     -> 0xFFF97316.toInt()  // Orange
            AlertType.PROTECT_CAPITAL -> 0xFFEAB308.toInt()  // Yellow
            AlertType.PEAK_WARNING    -> 0xFF8B5CF6.toInt()  // Purple
            AlertType.GOLDEN_BUY      -> 0xFFD97706.toInt()  // Gold
            AlertType.STRONG_BUY      -> 0xFF059669.toInt()  // Green
            else -> 0xFF64748B.toInt()
        }

        val levelTag = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> "⏱ Sell all — both scores firing"
            AlertType.BOOK_PROFIT     -> "⏱ Sell 50-70%, trail the rest"
            AlertType.PROTECT_CAPITAL -> "⏱ Sell entire position — trend broken"
            AlertType.PEAK_WARNING    -> "⏱ Prepare to sell — MACD slope may flip soon"
            else -> ""
        }

        val bigBody = buildString {
            append(alert.message)
            append("\n₹${String.format(Locale.US, "%.2f", alert.price)} · ${alert.macdPhase}")
            if (levelTag.isNotEmpty()) append("\n$levelTag")
        }

        val pi = PendingIntent.getActivity(this, alert.symbol.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(title).setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi)
            .setColor(alertColor)
            .setColorized(alert.alertType == AlertType.STRONG_EXIT)
            .setPriority(priority).setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .apply {
                if (config.vibrateOnAlerts) {
                    when (alert.alertType) {
                        AlertType.STRONG_EXIT     -> setVibrate(longArrayOf(0, 400, 150, 400, 150, 400))
                        AlertType.BOOK_PROFIT     -> setVibrate(longArrayOf(0, 300, 100, 300))
                        AlertType.PROTECT_CAPITAL -> setVibrate(longArrayOf(0, 250, 100, 250))
                        else -> {}
                    }
                }
            }
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(alert.symbol.hashCode() + 1000, notification)
    }

    private fun sendZerodhaExpiredNotification() {
        val pi = PendingIntent.getActivity(this, 9999, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("Zerodha session expired")
            .setContentText("Open SignalScope to reconnect your Zerodha account")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9999, notification)
    }

    private fun broadcastUpdate() {
        sendBroadcast(Intent(BROADCAST_SCAN_UPDATE).apply {
            putExtra(EXTRA_SCAN_STATUS, "scan_complete")
        })
    }

    private fun broadcastDiscoveryUpdate(market: String, progress: Int, total: Int, statusText: String = "") {
        sendBroadcast(Intent(BROADCAST_DISCOVERY_UPDATE).apply {
            putExtra(EXTRA_DISCOVERY_MARKET, market)
            putExtra(EXTRA_DISCOVERY_PROGRESS, progress)
            putExtra(EXTRA_DISCOVERY_TOTAL, total)
            putExtra(EXTRA_DISCOVERY_STATUS_TEXT, statusText)
        })
    }

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private fun buildScanResult(startTime: Long,
                                holdings: List<PortfolioHolding>, alerts: List<StockAlert>): ScanResult {
        val totalInvested = holdings.sumOf { it.invested }
        val totalCurrent = holdings.sumOf { it.currentVal }
        return ScanResult(market = "zerodha", startTime = startTime, totalScanned = holdings.size,
            holdings = holdings, alerts = alerts, durationMs = System.currentTimeMillis() - startTime,
            totalInvested = totalInvested, totalCurrent = totalCurrent, totalPnl = totalCurrent - totalInvested,
            sellAlertCount = alerts.count { it.alertType == AlertType.STRONG_EXIT || it.alertType == AlertType.BOOK_PROFIT || it.alertType == AlertType.PROTECT_CAPITAL })
    }

    private fun emptyScanResult(startTime: Long) = ScanResult(
        market = "zerodha", startTime = startTime, totalScanned = 0,
        holdings = emptyList(), alerts = emptyList(), durationMs = System.currentTimeMillis() - startTime)
}

// ═══════════════════════════════════════════════════════
// SYMBOL LISTS for discovery scanning
// ═══════════════════════════════════════════════════════

/**
 * Full NIFTY 500 universe for Yahoo Finance (.NS suffix added automatically).
 * Mirrors the Python app's FALLBACK_SET: NIFTY 50 + NIFTY Next 50 + BSE 100 +
 * Midcap 150 + Smallcap 250 = ~471 unique symbols.
 */
val NIFTY_500_YAHOO_SYMBOLS = listOf(
    // ── NIFTY 50 ──
    "ADANIENT","ADANIPORTS","APOLLOHOSP","ASIANPAINT","AXISBANK",
    "BAJAJ-AUTO","BAJFINANCE","BAJAJFINSV","BEL","BPCL",
    "BHARTIARTL","BRITANNIA","CIPLA","COALINDIA","DRREDDY",
    "EICHERMOT","ETERNAL","GRASIM","HCLTECH","HDFCBANK",
    "HDFCLIFE","HEROMOTOCO","HINDALCO","HINDUNILVR","ICICIBANK",
    "ITC","INDUSINDBK","INFY","JSWSTEEL","KOTAKBANK",
    "LT","M&M","MARUTI","NTPC","NESTLEIND",
    "ONGC","POWERGRID","RELIANCE","SBILIFE","SBIN",
    "SUNPHARMA","TCS","TATACONSUM","TATAMOTORS","TATASTEEL",
    "TECHM","TITAN","TRENT","ULTRACEMCO","WIPRO",

    // ── NIFTY NEXT 50 ──
    "ABB","ABBOTINDIA","AMBUJACEM","BAJAJHLDNG","BANKBARODA",
    "BERGEPAINT","BOSCHLTD","CANBK","CHOLAFIN","COLPAL",
    "DABUR","DIVISLAB","DLF","GAIL","GODREJCP",
    "HAVELLS","HINDPETRO","ICICIPRULI","INDHOTEL","IOC",
    "IRCTC","IRFC","JIOFIN","JSWENERGY","LICI",
    "LTIM","LUPIN","MARICO","MAXHEALTH","NHPC",
    "PFC","PIDILITIND","PNB","RECLTD","SBICARD",
    "SHREECEM","SHRIRAMFIN","SIEMENS","SRF","TORNTPHARM",
    "TVSMOTOR","UNIONBANK","UNITDSPR","VBL","VEDL",
    "YESBANK","ZOMATO","ZYDUSLIFE","IDEA","MOTHERSON",

    // ── BSE 100 extras ──
    "ADANIGREEN","ADANIPOWER","MUTHOOTFIN","CUMMINSIND","POLYCAB",
    "PERSISTENT","SUZLON","FEDERALBNK","INDUSTOWER","BSE",
    "BHEL","HDFCAMC","COFORGE","AUROPHARMA","TATAPOWER",
    "DIXON","IREDA","ACC","BIOCON","GODREJPROP",

    // ── NIFTY 100 / BSE extras ──
    "ADANIENERGY","ADANITOTAL","INDIANB","ASHOKLEY","PBFINTECH",
    "SWIGGY","AUBANK","IDFCFIRSTB","OBEROIRLTY","OFSS",
    "PIIND","ESCORTS","PAGEIND","MPHASIS","TATAELXSI",
    "KPITTECH","VOLTAS","LODHA","DELHIVERY","MRF",
    "SUNDARMFIN","LICHSGFIN","MFSL","NATIONALUM","HUDCO",
    "BANKINDIA","ALKEM","HINDCOPPER","SAIL","PETRONET",

    // ── NIFTY Midcap 150 extras ──
    "GUJGASLTD","SJVN","NMDC","BALKRISIND","SUPREMEIND",
    "APLAPOLLO","PHOENIXLTD","ASTRAL","JUBLFOOD","MANAPPURAM",
    "DEEPAKNTR","CROMPTON","SONACOMS","KALYANKJIL","METROBRAND",
    "BSOFT","PATANJALI","JSL","THERMAX","SYNGENE",
    "HONAUT","EXIDEIND","KEI","CGPOWER","PRESTIGE",
    "IPCALAB","CONCOR","BANDHANBNK","FORTIS","RBLBANK",
    "IDBIBANK","GMRAIRPORT","INDIAMART","ANGELONE","ZEEL",
    "LALPATHLAB","AJANTPHARM","TATACOMM","BDL","COCHINSHIP",
    "TATACHEM","ABCAPITAL","POONAWALLA","CAMS","CLEAN",
    "KAYNES","SUNTV","NAVINFLUOR","RVNL","TIINDIA",
    "ENDURANCE","SOLARINDS","NAUKRI","CENTRALBK","NIACL",
    "EMAMILTD","GLAXO","AIAENG","IIFL","RAJESHEXPO",
    "ABFRL","BLUESTARLT","KANSAINER","SUNDRMFAST","EIDPARRY",
    "MAHABANK","LINDEINDIA","BRIGADE","GRINDWELL","SCHAEFFLER",
    "APTUS","JKCEMENT","ASTRAZEN","CYIENT","NUVOCO",
    "AAVAS","HAPPSTMNDS","RATNAMANI","FIVESTAR","GPPL",
    "SUMICHEM","TRIVENI","ZENSARTECH","POLYMED","FINCABLES",
    "TIMKEN","PGHH","MCX","LAURUSLABS","CDSL",

    // ── NIFTY Smallcap 250 extras ──
    "GLAND","JBCHEPHARM","KARURVYSYA","RADICO","NARAYANA",
    "GRSE","MRPL","MSUMI","GILLETTE","DEVYANI",
    "SUNDRMHOLD","CHOLAHLDNG","ASTERDM","GODIGIT","SAPPHIRE",
    "SWANENERGY","ROUTE","LATENTVIEW","ISEC","DATAPATTNS",
    "FINEORG","WHIRLPOOL","MAHSEAMLESS","RENUKA","AFFLE",
    "TTML","TRITURBINE","EDELWEISS","NESCO","STARCEMENT",
    "CENTURYTEX","TEAMLEASE","MAPMYINDIA","MAHLIFE","NETWORK18",
    "PPLPHARMA","WESTLIFE","OLECTRA","JTLIND","BIKAJI",
    "TARSONS","KRSNAA","GRAVITA","TANLA","ECLERX",
    "CRAFTSMAN","SHYAMMETL","VGUARD","AMIORG","JYOTHYLAB",
    "STARHEALTH","MOTILALOFS","TTKPRESTIG","UTIAMC","NUVAMA",
    "PNBHOUSING","INDIACEM","ZFCVINDIA","ELGIEQUIP","SONATSOFTW",
    "GOCOLORS","GODFRYPHLP","CHALET","KIOCL","CERA",
    "ISGEC","TEGA","SUVENPHAR","SPARC","SBFC",
    "PRINCEPIPE","HBLPOWER","TV18BRDCST","RALLIS","FLUOROCHEM",
    "REDINGTON","BLUEDART","POWERINDIA","CARBORUNIV","CENTURYPLY",
    "PRSMJOHNSN","FINPIPE","MEDPLUS","ANURAS","SENCO",
    "GATEWAY","RAINBOW","AARTIIND","TCIEXP","MAHINDCIE",
    "MASTEK","ASAHIINDIA","LAXMIMACH","VSTIND","BALAMINES",
    "SUPRAJIT","KPIL","QUESS","AETHER","JKLAKSHMI",
    "NIITMTS","RAYMOND","WELCORP","KIMS","RATEGAIN",
    "SAREGAMA","MAHLOG","GLENMARK","IRCON","NATCOPHARM",
    "ZYDUSWELL","JSWINFRA","SHRIRAMCIT","TRIDENT","INOXWIND",
    "NCC","DCMSHRIRAM","RKFORGE","CHEMPLASTS","GMMPFAUDLR",
    "BLUEJET","BBTC","JAMNAAUTO","AARTIDRUGS","TDPOWERSYS",
    "GHCL","SPLPETRO","KFINTECH","ABSLAMC","SANOFI",
    "HINDWAREAP","SAKSOFT","RITES","PEL","LXCHEM",
    "NEWGEN","ACE","MASFIN","ENGINERSIN","SAGCEM",
    "IFBIND","DOMS","AVALON","SBCL","ADFFOODS",
    "VAIBHAVGBL","SAFARI","INDIGOPNTS","SHOPERSTOP","GREENPANEL",
    "PURMO","UCOBANK","CUB","MAZDOCK","BANARISUG",
    "TATAINVEST","RAMCOCEM","JKPAPER","SFL","CCL",
    "LUXIND","GARFIBRES","MIDHANI","DATAMATICS","VIPIND",
    "TVSSRICHAK","CASTROLIND","GULFOILLUB","ALKYLAMINE","ELECON",
    "PRAJIND","BAJAJELEC","PNCINFRA","SHILPAMED","WOCKPHARMA",
    "GESHIP","ORIENTELEC","GREENLAM","JKTYRE","DALBHARAT",
    "SKFINDIA","GMDCLTD","CAPACITE","SYMPHONY","SUNTECK",
    "SYRMA","INTELLECT"
).distinct()

val NASDAQ_100_SYMBOLS = listOf(
    "AAPL","ABNB","ADBE","ADI","ADP","ADSK","AEP","AMAT","AMD","AMGN",
    "AMZN","ANSS","APP","ARM","ASML","AVGO","AZN","BIIB","BKNG","BKR",
    "CCEP","CDNS","CDW","CEG","CHTR","CMCSA","COIN","COST","CPRT","CRWD",
    "CSCO","CSGP","CTAS","CTSH","DASH","DDOG","DLTR","DXCM","EA","EXC",
    "FANG","FAST","FTNT","GEHC","GFS","GILD","GOOG","GOOGL","HON","IDXX",
    "ILMN","INTC","INTU","ISRG","KDP","KHC","KLAC","LIN","LRCX","LULU",
    "MAR","MCHP","MDB","MDLZ","MELI","META","MNST","MRVL","MSFT","MU",
    "NFLX","NVDA","NXPI","ODFL","ON","ORLY","PANW","PAYX","PCAR","PDD",
    "PEP","PLTR","PYPL","QCOM","REGN","ROP","ROST","SBUX","SMCI","SNPS",
    "TEAM","TMUS","TSLA","TTD","TTWO","TXN","VRSK","VRTX","WBD","WDAY",
    "XEL","ZS"
)
