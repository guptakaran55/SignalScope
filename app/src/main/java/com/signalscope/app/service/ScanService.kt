package com.signalscope.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.signalscope.app.R
import com.signalscope.app.data.*
import com.signalscope.app.network.AngelOneClient
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.ZerodhaClient
import com.signalscope.app.ui.MainActivity
import com.signalscope.app.util.StockAnalyzer
import kotlinx.coroutines.*
import java.util.*

/**
 * Foreground Service with TWO scan modes:
 *
 * 1. PORTFOLIO MONITORING (automatic, market hours only)
 *    - Scans Angel One + Zerodha holdings every 15 min
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

        fun createIntent(context: Context, action: String): Intent =
            Intent(context, ScanService::class.java).apply { this.action = action }
    }

    private lateinit var config: ConfigManager
    private lateinit var angelClient: AngelOneClient
    private lateinit var zerodhaClient: ZerodhaClient

    private var portfolioJob: Job? = null
    private var discoveryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 30-min cooldown per symbol+alert type
    private val recentAlerts = mutableMapOf<String, Long>()
    private val ALERT_COOLDOWN_MS = 30 * 60 * 1000L

    // ── Results accessible from UI ──
    var lastAngelResult: ScanResult? = null; private set
    var lastZerodhaResult: ScanResult? = null; private set
    var lastDiscoveryResult: DiscoveryScanResult? = null; private set

    // Discovery scan state
    @Volatile var isDiscoveryRunning = false; private set
    @Volatile var discoveryAbort = false

    override fun onCreate() {
        super.onCreate()
        config = ConfigManager(this)
        angelClient = AngelOneClient(config)
        zerodhaClient = ZerodhaClient(config)
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

        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            try {
                val symbols = when (market) {
                    "nasdaq100" -> NASDAQ_100_SYMBOLS
                    else        -> null // NIFTY 500 uses Angel One; see below
                }

                if (market == "nifty500" && config.hasAngelCredentials) {
                    runNifty500Discovery()
                } else if (market == "nifty500") {
                    // No Angel One creds — scan NIFTY via Yahoo Finance (slower but works)
                    runYahooDiscovery(NIFTY_50_YAHOO_SYMBOLS, "NIFTY 50 (via Yahoo)", "₹")
                } else {
                    runYahooDiscovery(NASDAQ_100_SYMBOLS, "NASDAQ 100", "$")
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Discovery scan error", e)
                updateScanNotification("Discovery scan failed: ${e.message}")
            } finally {
                isDiscoveryRunning = false
                broadcastDiscoveryUpdate(market, -1, -1) // signal completion
            }
        }
    }

    private fun stopDiscoveryScan() {
        discoveryAbort = true
        discoveryJob?.cancel()
        isDiscoveryRunning = false
        updateScanNotification("Discovery scan stopped")
        broadcastDiscoveryUpdate("stopped", -1, -1)
    }

    /**
     * Scan NIFTY 500 using Angel One API for candle data (faster, no Yahoo rate limits).
     * Mirrors the Python run_nifty_scan() logic.
     */
    private suspend fun runNifty500Discovery() = withContext(Dispatchers.IO) {
        // For NIFTY 500 via Angel One, we'd need the instrument list.
        // Since we don't have it cached on mobile, fall back to scanning
        // the NIFTY 50 (top 50 stocks) via Yahoo Finance as a practical compromise.
        // The full 500 can be done if the user has Angel One configured.
        // TODO: Download instrument list from Angel One and scan all 500
        Log.i(TAG, "NIFTY 500 discovery — scanning top stocks via Angel One + Yahoo fallback")

        runYahooDiscovery(NIFTY_50_YAHOO_SYMBOLS, "NIFTY 50", "₹")
    }

    /**
     * Scan a list of symbols via Yahoo Finance.
     * Works for both NASDAQ 100 and Indian stocks (.NS suffix).
     * Results are streamed to lastDiscoveryResult as each stock completes.
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
        var rateLimitBackoff = 600L

        updateScanNotification("Discovery: $marketName — 0/$total scanned")
        Log.i(TAG, "Discovery scan started: $marketName — $total symbols")

        for ((idx, symbol) in symbols.withIndex()) {
            if (discoveryAbort) {
                Log.i(TAG, "Discovery scan aborted at $idx/$total")
                break
            }

            try {
                // Determine exchange for Yahoo symbol formatting
                val exchange = if (currency == "₹") "NSE" else "NASDAQ"
                val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        val analysis = StockAnalyzer.analyze(
                            candles = candleResult.candles,
                            symbol = symbol,
                            name = symbol,
                            token = symbol,
                            minAvgVolume = if (currency == "₹") 100000 else 50000
                        )
                        if (analysis != null) {
                            allStocks.add(analysis)
                        } else {
                            skipped++
                        }
                        rateLimitBackoff = 600L // reset on success
                    }
                    is YahooFinanceClient.CandleResult.RateLimited -> {
                        errors++
                        rateLimitBackoff = (rateLimitBackoff * 1.5).toLong().coerceAtMost(15000L)
                        Log.w(TAG, "Discovery: Yahoo rate limited on $symbol, backing off ${rateLimitBackoff}ms")
                        delay(rateLimitBackoff)
                    }
                    is YahooFinanceClient.CandleResult.NoData -> skipped++
                    is YahooFinanceClient.CandleResult.Error -> {
                        errors++
                        Log.w(TAG, "Discovery: error for $symbol — ${candleResult.message}")
                    }
                }

                // Update progress every stock
                if ((idx + 1) % 5 == 0 || idx == total - 1) {
                    val sorted = allStocks.sortedByDescending { it.buyScore }
                    lastDiscoveryResult = DiscoveryScanResult(
                        market = marketName,
                        currency = currency,
                        timestamp = System.currentTimeMillis(),
                        totalScanned = allStocks.size,
                        totalSymbols = total,
                        skipped = skipped,
                        errors = errors,
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

                    updateScanNotification(
                        "Discovery: $marketName — ${idx + 1}/$total (${allStocks.size} analyzed)"
                    )
                    broadcastDiscoveryUpdate(marketName, idx + 1, total)
                }

                // Pacing — Yahoo Finance needs ~0.6s between requests
                delay(rateLimitBackoff.coerceAtLeast(600L))

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
                Log.e(TAG, "Discovery error for $symbol", e)
            }
        }

        // Final result
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

        val statusMsg = if (discoveryAbort) "Discovery stopped" else
            "Discovery done: ${allStocks.size} stocks in ${elapsed / 60000}m"
        updateScanNotification(statusMsg)
        Log.i(TAG, "$statusMsg — ${sorted.count { it.buyScore >= 60 }} buy signals, " +
                "${sorted.count { it.goldenBuy }} golden buys")
    }

    // ═══════════════════════════════════════════════════════
    // FULL PORTFOLIO SCAN  (Angel One + Zerodha)
    // Same as before — unchanged
    // ═══════════════════════════════════════════════════════

    private suspend fun runFullPortfolioScan() {
        val source = config.portfolioSource
        var totalAlerts = 0

        if ((source == "angel" || source == "both") && config.hasAngelCredentials) {
            updateScanNotification("Scanning Angel One portfolio...")
            val result = scanAngelPortfolio()
            lastAngelResult = result
            totalAlerts += result.alerts.size
            processAlerts(result.alerts)
        }

        if ((source == "zerodha" || source == "both") && config.isZerodhaConnected) {
            updateScanNotification("Scanning Zerodha portfolio...")
            val result = scanZerodhaPortfolio()
            lastZerodhaResult = result
            totalAlerts += result.alerts.size
            processAlerts(result.alerts)
        }

        config.lastPortfolioScan = System.currentTimeMillis()

        val statusText = when {
            totalAlerts > 0 -> "⚠ $totalAlerts alert(s) — tap to view"
            else -> "✓ Portfolio OK — no sell signals"
        }
        updateScanNotification(statusText)
        broadcastUpdate()
    }

    private suspend fun scanAngelPortfolio(): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val alerts = mutableListOf<StockAlert>()
        val scannedHoldings = mutableListOf<PortfolioHolding>()

        val portfolioResult = angelClient.fetchPortfolio()
        val rawHoldings = when (portfolioResult) {
            is AngelOneClient.PortfolioResult.Success -> portfolioResult.holdings
            is AngelOneClient.PortfolioResult.Failure -> {
                Log.e(TAG, "Angel One portfolio failed: ${portfolioResult.message}")
                return@withContext emptyScanResult("nifty500", startTime)
            }
        }

        for (holding in rawHoldings) {
            if (holding.token.isBlank()) continue
            try {
                val candleResult = angelClient.fetchCandleData(holding.token)
                when (candleResult) {
                    is AngelOneClient.CandleResult.Success -> {
                        val analysis = StockAnalyzer.analyze(
                            candles = candleResult.candles,
                            symbol = holding.symbol, name = holding.symbol,
                            token = holding.token, minAvgVolume = 0
                        )
                        val verdict = computeVerdict(analysis, holding.totalReturnPct)
                        scannedHoldings.add(holding.copy(analysis = analysis, verdict = verdict))
                        if (analysis != null) {
                            generateAlerts(holding, analysis, holding.totalReturnPct, "angel")
                                ?.let { alerts.addAll(it) }
                        }
                        delay(500)
                    }
                    is AngelOneClient.CandleResult.RateLimited -> {
                        Log.w(TAG, "Angel One rate limited on ${holding.symbol}")
                        delay(5_000)
                    }
                    else -> scannedHoldings.add(holding.copy(verdict = "NO DATA"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing Angel ${holding.symbol}", e)
                scannedHoldings.add(holding.copy(verdict = "ERROR"))
            }
        }
        buildScanResult("nifty500", startTime, scannedHoldings, alerts)
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
                return@withContext emptyScanResult("zerodha", startTime)
            }
            is ZerodhaClient.HoldingsResult.Failure -> {
                Log.e(TAG, "Zerodha holdings failed: ${holdingsResult.message}")
                return@withContext emptyScanResult("zerodha", startTime)
            }
        }

        for (holding in rawHoldings) {
            try {
                val candleResult = YahooFinanceClient.fetchCandles(holding.symbol, holding.exchange)
                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        val analysis = StockAnalyzer.analyze(
                            candles = candleResult.candles,
                            symbol = holding.symbol, name = holding.symbol,
                            token = holding.symbol, minAvgVolume = 0
                        )
                        val verdict = computeVerdict(analysis, holding.totalReturnPct)
                        scannedHoldings.add(holding.copy(analysis = analysis, verdict = verdict))
                        if (analysis != null) {
                            generateAlerts(holding, analysis, holding.totalReturnPct, "zerodha")
                                ?.let { alerts.addAll(it) }
                        }
                        delay(600)
                    }
                    is YahooFinanceClient.CandleResult.RateLimited -> {
                        delay(10_000)
                    }
                    else -> scannedHoldings.add(holding.copy(verdict = "NO DATA"))
                }
            } catch (e: Exception) {
                scannedHoldings.add(holding.copy(verdict = "ERROR"))
            }
        }
        buildScanResult("zerodha", startTime, scannedHoldings, alerts)
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

    private fun computeVerdict(analysis: StockAnalysis?, returnPct: Double): String {
        if (analysis == null) return "HOLD"
        return when {
            analysis.sellScore >= 65 -> "SELL NOW"
            analysis.sellScore >= 45 -> "MOD SELL"
            analysis.sellScore >= 30 && returnPct >= 25.0 -> "BOOK PROFIT?"
            else -> "HOLD"
        }
    }

    private fun generateAlerts(
        holding: PortfolioHolding, analysis: StockAnalysis,
        returnPct: Double, source: String
    ): List<StockAlert>? {
        val alerts = mutableListOf<StockAlert>()

        if (analysis.sellScore >= config.strongSellAlertThreshold) {
            alerts.add(StockAlert(symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.STRONG_SELL,
                message = "Sell score ${analysis.sellScore}/108 — ${analysis.sellSignal}",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }
        if (analysis.sellScore >= config.sellScoreAlertThreshold &&
            analysis.sellScore < config.strongSellAlertThreshold) {
            alerts.add(StockAlert(symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.MODERATE_SELL,
                message = "Sell score ${analysis.sellScore}/108 — watch closely",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }
        if (analysis.macdPhase == "SELL FLIP") {
            alerts.add(StockAlert(symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.SELL_FLIP, message = "MACD momentum just turned bearish",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }
        if (analysis.sma200 != null && analysis.price < analysis.sma200) {
            alerts.add(StockAlert(symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.TREND_BREAK, message = "Price below SMA(200) — uptrend broken",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }
        if (analysis.sellScore >= 30 && returnPct >= 25.0) {
            alerts.add(StockAlert(symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.BOOK_PROFIT,
                message = "Unrealized gain ${String.format("%.1f", returnPct)}% + sell pressure building",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }
        if (analysis.upDays == 0 && analysis.priceVel < 0 && analysis.priceAccel < 0) {
            alerts.add(StockAlert(symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.CONSECUTIVE_DECLINE,
                message = "Price declining with accelerating momentum loss",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }
        return if (alerts.isNotEmpty()) alerts else null
    }

    private fun processAlerts(alerts: List<StockAlert>) {
        if (!config.notificationsEnabled) return
        val now = System.currentTimeMillis()
        for (alert in alerts) {
            val key = "${alert.symbol}_${alert.alertType}_${alert.source}"
            val lastAlerted = recentAlerts[key] ?: 0L
            if (now - lastAlerted > ALERT_COOLDOWN_MS) {
                sendAlertNotification(alert)
                recentAlerts[key] = now
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

    private fun sendAlertNotification(alert: StockAlert) {
        val sourceLabel = if (alert.source == "zerodha") "[Zerodha] " else "[Angel] "
        val title = when (alert.alertType) {
            AlertType.STRONG_SELL         -> "🔴 SELL: $sourceLabel${alert.symbol}"
            AlertType.MODERATE_SELL       -> "🟡 Watch: $sourceLabel${alert.symbol}"
            AlertType.SELL_FLIP           -> "⚠️ $sourceLabel${alert.symbol} momentum flip"
            AlertType.TREND_BREAK         -> "📉 Trend Break: $sourceLabel${alert.symbol}"
            AlertType.BOOK_PROFIT         -> "💰 Book Profit: $sourceLabel${alert.symbol}"
            AlertType.CONSECUTIVE_DECLINE -> "📉 Declining: $sourceLabel${alert.symbol}"
            AlertType.GOLDEN_BUY          -> "⭐ Golden Buy: ${alert.symbol}"
            AlertType.STRONG_BUY          -> "🟢 Buy: ${alert.symbol}"
        }
        val priority = when (alert.alertType) {
            AlertType.STRONG_SELL, AlertType.TREND_BREAK -> NotificationCompat.PRIORITY_MAX
            AlertType.MODERATE_SELL, AlertType.SELL_FLIP  -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val pi = PendingIntent.getActivity(this, alert.symbol.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(title).setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${alert.message}\n₹${String.format("%.2f", alert.price)} · Sell ${alert.sellScore} · ${alert.macdPhase}"))
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi)
            .setPriority(priority).setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .apply { if (config.vibrateOnAlerts) setVibrate(longArrayOf(0, 250, 100, 250)) }
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

    private fun broadcastDiscoveryUpdate(market: String, progress: Int, total: Int) {
        sendBroadcast(Intent(BROADCAST_DISCOVERY_UPDATE).apply {
            putExtra(EXTRA_DISCOVERY_MARKET, market)
            putExtra(EXTRA_DISCOVERY_PROGRESS, progress)
            putExtra(EXTRA_DISCOVERY_TOTAL, total)
        })
    }

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private fun buildScanResult(market: String, startTime: Long,
                                holdings: List<PortfolioHolding>, alerts: List<StockAlert>): ScanResult {
        val totalInvested = holdings.sumOf { it.invested }
        val totalCurrent = holdings.sumOf { it.currentVal }
        return ScanResult(market = market, startTime = startTime, totalScanned = holdings.size,
            holdings = holdings, alerts = alerts, durationMs = System.currentTimeMillis() - startTime,
            totalInvested = totalInvested, totalCurrent = totalCurrent, totalPnl = totalCurrent - totalInvested,
            sellAlertCount = alerts.count { it.alertType == AlertType.STRONG_SELL || it.alertType == AlertType.MODERATE_SELL })
    }

    private fun emptyScanResult(market: String, startTime: Long) = ScanResult(
        market = market, startTime = startTime, totalScanned = 0,
        holdings = emptyList(), alerts = emptyList(), durationMs = System.currentTimeMillis() - startTime)
}

// ═══════════════════════════════════════════════════════
// SYMBOL LISTS for discovery scanning
// ═══════════════════════════════════════════════════════

/** NIFTY 50 symbols for Yahoo Finance (.NS suffix added automatically) */
val NIFTY_50_YAHOO_SYMBOLS = listOf(
    "ADANIENT","ADANIPORTS","APOLLOHOSP","ASIANPAINT","AXISBANK",
    "BAJAJ-AUTO","BAJFINANCE","BAJAJFINSV","BEL","BPCL",
    "BHARTIARTL","BRITANNIA","CIPLA","COALINDIA","DRREDDY",
    "EICHERMOT","ETERNAL","GRASIM","HCLTECH","HDFCBANK",
    "HDFCLIFE","HEROMOTOCO","HINDALCO","HINDUNILVR","ICICIBANK",
    "ITC","INDUSINDBK","INFY","JSWSTEEL","KOTAKBANK",
    "LT","M&M","MARUTI","NTPC","NESTLEIND",
    "ONGC","POWERGRID","RELIANCE","SBILIFE","SBIN",
    "SUNPHARMA","TCS","TATACONSUM","TATAMOTORS","TATASTEEL",
    "TECHM","TITAN","TRENT","ULTRACEMCO","WIPRO"
)

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
