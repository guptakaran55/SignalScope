package com.signalscope.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.DiscoveryResultStore
import com.signalscope.app.data.DiscoveryScanResult
import com.signalscope.app.service.ScanService
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.StockAiClient
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * Main activity — hosts a WebView that renders the full SignalScope dashboard.
 *
 * Architecture:
 *   - dashboard.html (loaded from assets) renders the stock table, detail modal, etc.
 *   - Kotlin pushes scan results into the WebView via evaluateJavascript()
 *   - dashboard.html calls back into Kotlin via @JavascriptInterface for actions
 *
 * The top bar with scan controls is native Android (buttons stay responsive
 * even while the WebView is rendering a large table).
 * Everything below the controls is the WebView dashboard.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var config: ConfigManager
    private lateinit var webView: WebView
    private lateinit var btnMonitor: MaterialButton
    private lateinit var btnNifty: MaterialButton
    private lateinit var btnNasdaq: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var txtStatus: TextView
    private lateinit var controlBar: LinearLayout

    private val gson = Gson()
    private var isServiceRunning = false

    // ── Polling-based UI refresh (replaces unreliable broadcasts on MIUI) ──
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSeenDiscoveryVersion = 0L
    private var lastSeenPortfolioVersion = 0L
    private var wasDiscoveryRunning = false
    private val POLL_INTERVAL_MS = 500L // 500ms — keeps UI in sync with notification progress

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollForUpdates()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun pollForUpdates() {
        val holder = ScanServiceResultHolder

        // Check for discovery updates
        if (holder.discoveryVersion != lastSeenDiscoveryVersion) {
            lastSeenDiscoveryVersion = holder.discoveryVersion

            refreshStatusBar()

            if (holder.isDiscoveryRunning) {
                wasDiscoveryRunning = true
                val p = holder.discoveryProgress
                val t = holder.discoveryTotal
                val m = holder.discoveryMarket
                webView.evaluateJavascript(
                    "window.updateProgress($p, $t, '${m.replace("'", "\\'")}')", null
                )
                pushDiscoveryResultsToWebView()
            } else if (wasDiscoveryRunning) {
                // Scan just finished
                wasDiscoveryRunning = false
                pushDiscoveryResultsToWebView()
                webView.evaluateJavascript("window.scanComplete()", null)
                btnStop.visibility = View.GONE
            }
        }

        // Check for portfolio updates
        if (holder.portfolioVersion != lastSeenPortfolioVersion) {
            lastSeenPortfolioVersion = holder.portfolioVersion
            refreshStatusBar()
            pushPortfolioResultsToWebView()
        }
    }

    // Keep broadcast receiver as fallback for immediate delivery when it works
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScanService.BROADCAST_SCAN_UPDATE -> {
                    refreshStatusBar()
                    pushPortfolioResultsToWebView()
                    lastSeenPortfolioVersion = ScanServiceResultHolder.portfolioVersion
                }
                ScanService.BROADCAST_DISCOVERY_UPDATE -> {
                    // Force an immediate poll cycle so updates are instant when broadcasts work
                    pollForUpdates()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()

        config = ConfigManager(this)
        setupWebView()
        setupButtons()
        requestNotificationPermission()
        refreshStatusBar()

        // Load cached discovery results from disk so they survive process death
        if (ScanServiceResultHolder.lastDiscoveryResult == null) {
            val cached = DiscoveryResultStore.loadLatest(this)
            if (cached != null) {
                ScanServiceResultHolder.lastDiscoveryResult = cached
                Log.d(TAG, "Restored ${cached.allStocks.size} cached discovery stocks (${cached.market})")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ScanService.BROADCAST_SCAN_UPDATE)
            addAction(ScanService.BROADCAST_DISCOVERY_UPDATE)
        }
        ContextCompat.registerReceiver(this, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isServiceRunning = config.serviceRunning

        // Restore from disk if in-memory results were lost (process death)
        if (ScanServiceResultHolder.lastDiscoveryResult == null) {
            val cached = DiscoveryResultStore.loadLatest(this)
            if (cached != null) {
                ScanServiceResultHolder.lastDiscoveryResult = cached
                Log.d(TAG, "Restored ${cached.allStocks.size} cached discovery stocks (${cached.market})")
            }
        }

        // Track if discovery is currently running
        wasDiscoveryRunning = ScanServiceResultHolder.isDiscoveryRunning
        btnStop.visibility = if (wasDiscoveryRunning) View.VISIBLE else View.GONE

        refreshStatusBar()

        // Push whatever results we have (live or cached) to WebView
        pushDiscoveryResultsToWebView()
        pushPortfolioResultsToWebView()

        // Start polling for live updates (reliable even on MIUI)
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════
    // LAYOUT (programmatic — no XML dependency)
    // ═══════════════════════════════════════════════════════

    private fun buildLayout() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFf8fafc.toInt())
        }

        // ── Top bar: status + controls ──
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFffffff.toInt())
        }

        // Status row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Logo ImageView — loads assets/logo.png (same image used inside the WebView).
        // Falls back to the launcher icon if the asset is missing.
        val logo = ImageView(this).apply {
            try {
                assets.open("logo.png").use {
                    setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                }
            } catch (e: Exception) {
                Log.w(TAG, "logo.png not found in assets, falling back to launcher icon", e)
                setImageResource(com.signalscope.app.R.mipmap.ic_launcher)
            }
            val size = dp(30)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "SignalScope"
        }
        statusRow.addView(logo)

        txtStatus = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(0xFF0f172a.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(txtStatus)

        val btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        statusRow.addView(btnSettings)
        controlBar.addView(statusRow)

        // Button row 1: Monitor toggle
        btnMonitor = MaterialButton(this).apply {
            text = "▶  Start Monitoring"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF059669.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { topMargin = dp(8) }
        }
        controlBar.addView(btnMonitor)

        // Button row 2: Discovery scans
        val discRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        btnNifty = MaterialButton(this).apply {
            text = "🇮🇳 NIFTY 500"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFF2563eb.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) }
        }
        discRow.addView(btnNifty)

        btnNasdaq = MaterialButton(this).apply {
            text = "🇺🇸 NASDAQ 100"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFF2563eb.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) }
        }
        discRow.addView(btnNasdaq)
        controlBar.addView(discRow)

        // Stop button (hidden by default)
        btnStop = MaterialButton(this).apply {
            text = "⏹ Stop Scan"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFFdc2626.toInt())
            cornerRadius = dp(8)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
            ).apply { topMargin = dp(4) }
        }
        controlBar.addView(btnStop)

        root.addView(controlBar)

        // ── WebView: the dashboard ──
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(0xFFf8fafc.toInt())
        }
        root.addView(webView)

        setContentView(root)
    }

    // ═══════════════════════════════════════════════════════
    // WEBVIEW SETUP
    // ═══════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true // needed for file:///android_asset/dashboard.html
            allowFileAccessFromFileURLs = false // block JS from reading other local files
            allowUniversalAccessFromFileURLs = false // block cross-origin file access
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }

        // Add JS bridge so dashboard.html can call Kotlin
        webView.addJavascriptInterface(DashboardBridge(), "Android")

        // Load dashboard from assets
        webView.loadUrl("file:///android_asset/dashboard.html")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Push any existing results once page is loaded
                pushDiscoveryResultsToWebView()
                pushPortfolioResultsToWebView()
                // Fetch sector data in background
                fetchSectorData()
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // JS BRIDGE — dashboard.html can call these from JavaScript
    // ═══════════════════════════════════════════════════════

    inner class DashboardBridge {

        @JavascriptInterface
        fun triggerNiftyScan() {
            runOnUiThread { startDiscoveryScan(ScanService.ACTION_DISCOVERY_NIFTY500) }
        }

        @JavascriptInterface
        fun triggerNasdaqScan() {
            runOnUiThread { startDiscoveryScan(ScanService.ACTION_DISCOVERY_NASDAQ100) }
        }

        @JavascriptInterface
        fun stopScan() {
            runOnUiThread { stopDiscoveryScan() }
        }

        @JavascriptInterface
        fun triggerPortfolioScan() {
            runOnUiThread {
                // Ensure service is running before triggering portfolio scan
                ensureServiceRunning()
                startService(ScanService.createIntent(this@MainActivity, ScanService.ACTION_SCAN_NOW))
                Toast.makeText(this@MainActivity, "Scanning portfolio...", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun fetchSectors() {
            fetchSectorData()
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        @JavascriptInterface
        fun openDocumentation() {
            runOnUiThread {
                try {
                    // Copy PDF from assets to cache dir so it can be opened via FileProvider/Intent
                    val pdfFile = java.io.File(cacheDir, "SignalScope_Documentation.pdf")
                    assets.open("SignalScope_Documentation.pdf").use { input ->
                        pdfFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "$packageName.fileprovider",
                        pdfFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open Documentation"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "No PDF viewer found. Install a PDF reader app.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun getLastScanTime(): String {
            val ts = config.lastPortfolioScan
            return if (ts > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)) else "Never"
        }

        @JavascriptInterface
        fun hasAiEnabled(): Boolean = config.hasLlmCredentials

        /** Called from detail modal — analyzes why a stock has pulled back */
        @JavascriptInterface
        fun analyzePullback(symbol: String, price: Double, ema21PctDiff: Double,
                            macdPhase: String, profitScore: Int, protectScore: Int) {
            thread(isDaemon = true) {
                // Guard the ENTIRE body — any unexpected exception must still
                // deliver a callback, otherwise the spinner spins forever.
                val text: String = try {
                    val result = StockAiClient.analyzePullback(
                        config, symbol, price, ema21PctDiff, macdPhase, profitScore, protectScore
                    )
                    when (result) {
                        is StockAiClient.AiResult.Success -> result.text
                        is StockAiClient.AiResult.Error -> "⚠ ${result.message}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Pullback thread crashed for $symbol", e)
                    "⚠ AI analysis crashed: ${e.message ?: e.javaClass.simpleName}"
                }
                deliverAiCallback("window.onPullbackResult", text)
            }
        }

        /** Called from detail modal — full stock outlook analysis */
        @JavascriptInterface
        fun analyzeOutlook(symbol: String, price: Double, buyScore: Int,
                           profitScore: Int, protectScore: Int, sellIntent: String,
                           macdPhase: String, macdSlope: Double, rsi: Double,
                           sma200: Double, ema21PctDiff: Double, rrRatio: Double, priceVel: Double) {
            thread(isDaemon = true) {
                val text: String = try {
                    val result = StockAiClient.analyzeOutlook(
                        config, symbol, price, buyScore, profitScore, protectScore, sellIntent,
                        macdPhase, macdSlope, if (rsi == 0.0) null else rsi,
                        if (sma200 == 0.0) null else sma200,
                        ema21PctDiff, rrRatio, priceVel
                    )
                    when (result) {
                        is StockAiClient.AiResult.Success -> result.text
                        is StockAiClient.AiResult.Error -> "⚠ ${result.message}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Outlook thread crashed for $symbol", e)
                    "⚠ AI analysis crashed: ${e.message ?: e.javaClass.simpleName}"
                }
                deliverAiCallback("window.onOutlookResult", text)
            }
        }

        /**
         * Deliver a text payload to a WebView JS callback. Escapes defensively and guards
         * against the activity being destroyed mid-flight (old callback should no-op, not crash).
         */
        private fun deliverAiCallback(jsFn: String, text: String) {
            val escaped = try {
                text.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "")
            } catch (e: Throwable) {
                Log.e(TAG, "Escape failed", e)
                "⚠ result encoding failed"
            }
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed) {
                        webView.evaluateJavascript("$jsFn('$escaped')", null)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "evaluateJavascript failed for $jsFn", e)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // BUTTON HANDLERS
    // ═══════════════════════════════════════════════════════

    private fun setupButtons() {
        btnMonitor.setOnClickListener {
            if (!config.isZerodhaConnected) {
                Toast.makeText(this, "Configure credentials in Settings first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            if (isServiceRunning) stopMonitoring() else startMonitoring()
        }

        btnNifty.setOnClickListener {
            startDiscoveryScan(ScanService.ACTION_DISCOVERY_NIFTY500)
        }

        btnNasdaq.setOnClickListener {
            startDiscoveryScan(ScanService.ACTION_DISCOVERY_NASDAQ100)
        }

        btnStop.setOnClickListener {
            stopDiscoveryScan()
        }
    }

    // ═══════════════════════════════════════════════════════
    // SERVICE CONTROL
    // ═══════════════════════════════════════════════════════

    private fun ensureServiceRunning() {
        if (!isServiceRunning) {
            ContextCompat.startForegroundService(this,
                ScanService.createIntent(this, ScanService.ACTION_START))
            isServiceRunning = true
            config.serviceRunning = true
        }
    }

    private fun startMonitoring() {
        ensureServiceRunning()
        requestBatteryOptimizationExemption()
        refreshStatusBar()
        Toast.makeText(this, "Portfolio monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        startService(ScanService.createIntent(this, ScanService.ACTION_STOP))
        isServiceRunning = false
        config.serviceRunning = false
        refreshStatusBar()
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
    }

    private fun startDiscoveryScan(action: String) {
        ensureServiceRunning()
        startService(ScanService.createIntent(this, action))
        btnStop.visibility = View.VISIBLE
        val market = if (action.contains("NIFTY")) "NIFTY" else "NASDAQ 100"
        Toast.makeText(this, "$market discovery scan starting...", Toast.LENGTH_SHORT).show()
    }

    private fun stopDiscoveryScan() {
        startService(ScanService.createIntent(this, ScanService.ACTION_DISCOVERY_STOP))
        btnStop.visibility = View.GONE
        Toast.makeText(this, "Discovery scan stopping...", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════
    // PUSH DATA TO WEBVIEW
    // ═══════════════════════════════════════════════════════

    private fun pushDiscoveryResultsToWebView() {
        // Access the service's static result holder
        val result = ScanServiceResultHolder.lastDiscoveryResult ?: return
        try {
            val payload = mapOf(
                "allStocks" to result.allStocks,
                "market" to result.market,
                "currency" to result.currency,
                "errors" to result.errors,
                "skipped" to result.skipped,
                "lastError" to result.lastError,
                "isScanning" to !result.isComplete,
                // Use discoveryProgress (stocks attempted = idx+1) so the app screen matches
                // the notification, which also counts attempts. totalScanned only counts
                // successfully analysed stocks, so it's always lower when rate-limits occur.
                "progress" to if (!result.isComplete) mapOf(
                    "current" to ScanServiceResultHolder.discoveryProgress,
                    "total" to result.totalSymbols
                ) else null
            )
            val json = gson.toJson(payload)
            // Escape for JS string
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.updateScanData('$escaped')", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing results to WebView", e)
        }
    }

    private fun pushPortfolioResultsToWebView() {
        val zerodha = ScanServiceResultHolder.lastZerodhaResult ?: return
        val allHoldings = mutableListOf<Map<String, Any?>>()

        zerodha.holdings.forEach { h ->
            val a = h.analysis
            allHoldings.add(mapOf(
                "symbol" to h.symbol,
                "exchange" to h.exchange,
                "quantity" to h.quantity,
                "avgPrice" to h.avgPrice,
                "ltp" to h.ltp,
                "pnl" to h.pnl,
                "dayChange" to h.dayChange,
                "dayChangePct" to h.dayChangePct,
                "invested" to h.invested,
                "currentVal" to h.currentVal,
                "totalReturnPct" to h.totalReturnPct,
                "source" to "zerodha",
                "verdict" to h.verdict,
                "buyScore" to (a?.buyScore ?: 0),
                "profitScore" to (a?.profitScore ?: 0),
                "protectScore" to (a?.protectScore ?: 0),
                "sellIntent" to (a?.sellIntent ?: "HOLD"),
                "sellScore" to (a?.sellScore ?: 0),
                "macdPhase" to (a?.macdPhase ?: "—"),
                "macdSlope" to (a?.macdSlope ?: 0.0),
                "macdCurve" to (a?.macdCurve ?: emptyList<Double>()),
                "buySignal" to (a?.buySignal ?: "—"),
                "sellSignal" to (a?.sellSignal ?: "—"),
                "support" to (a?.support ?: 0.0),
                "resistance" to (a?.resistance ?: 0.0),
                "projectedCeiling" to (a?.projectedCeiling),
                "projectedFloor" to (a?.projectedFloor),
                "projectedMidpoint" to (a?.projectedMidpoint),
                "hasAnalysis" to (a != null)
            ))
        }

        if (allHoldings.isEmpty()) return

        try {
            val totalInvested = allHoldings.sumOf { (it["invested"] as? Double) ?: 0.0 }
            val totalCurrent = allHoldings.sumOf { (it["currentVal"] as? Double) ?: 0.0 }
            val totalPnl = totalCurrent - totalInvested
            val dayPnl = allHoldings.sumOf { (it["dayChange"] as? Double) ?: 0.0 }
            val sellAlerts = allHoldings.count {
                val intent = (it["sellIntent"] as? String) ?: "HOLD"
                intent != "HOLD"
            }

            val payload = mapOf(
                "holdings" to allHoldings,
                "summary" to mapOf(
                    "count" to allHoldings.size,
                    "invested" to totalInvested,
                    "current" to totalCurrent,
                    "totalPnl" to totalPnl,
                    "totalReturnPct" to if (totalInvested > 0) (totalPnl / totalInvested * 100) else 0.0,
                    "dayPnl" to dayPnl,
                    "sellAlerts" to sellAlerts
                )
            )
            val json = gson.toJson(payload)
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.updatePortfolioData('$escaped')", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing portfolio to WebView", e)
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTOR DATA — fetched from Yahoo Finance in background
    // ═══════════════════════════════════════════════════════

    private val sectorIndices = listOf(
        Triple("Banking", "^NSEBANK", "NIFTY Bank"),
        Triple("IT", "^CNXIT", "NIFTY IT"),
        Triple("Pharma", "^CNXPHARMA", "NIFTY Pharma"),
        Triple("FMCG", "^CNXFMCG", "NIFTY FMCG"),
        Triple("Auto", "^CNXAUTO", "NIFTY Auto"),
        Triple("Energy", "^CNXENERGY", "NIFTY Energy"),
        Triple("Fin Services", "^CNXFIN", "NIFTY Fin Service"),
        Triple("Metal", "^CNXMETAL", "NIFTY Metal"),
        Triple("Realty", "^CNXREALTY", "NIFTY Realty"),
        Triple("Media", "^CNXMEDIA", "NIFTY Media")
    )

    @Volatile private var sectorFetchInProgress = false

    private fun fetchSectorData() {
        if (sectorFetchInProgress) return
        sectorFetchInProgress = true

        thread(isDaemon = true) {
            try {
                val sectors = mutableListOf<Map<String, Any?>>()

                for ((name, symbol, index) in sectorIndices) {
                    try {
                        val result = YahooFinanceClient.fetchCandles(symbol, "NSE")
                        if (result is YahooFinanceClient.CandleResult.Success && result.candles.size >= 2) {
                            val candles = result.candles
                            val latest = candles.last()
                            val prev = candles[candles.size - 2]
                            val dayChange = if (prev.close > 0) (latest.close - prev.close) / prev.close * 100.0 else 0.0

                            // Period stats (last 6 months or available)
                            val periodStart = if (candles.size > 120) candles[candles.size - 120] else candles.first()
                            val periodChange = if (periodStart.close > 0) (latest.close - periodStart.close) / periodStart.close * 100.0 else 0.0
                            val high = candles.takeLast(120).maxOfOrNull { it.high } ?: latest.high
                            val low = candles.takeLast(120).minOfOrNull { it.low } ?: latest.low

                            // Last 60 closing prices for sparkline chart
                            val closesForChart = candles.takeLast(60).map { it.close }

                            sectors.add(mapOf(
                                "name" to name,
                                "index" to index,
                                "symbol" to symbol,
                                "price" to latest.close,
                                "dayChange" to dayChange,
                                "periodChange" to periodChange,
                                "high" to high,
                                "low" to low,
                                "closes" to closesForChart
                            ))
                        }
                        Thread.sleep(600) // pace requests
                    } catch (e: Exception) {
                        Log.w(TAG, "Sector fetch error for $name", e)
                    }
                }

                if (sectors.isNotEmpty()) {
                    val json = gson.toJson(sectors)
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript("window.updateSectorData('$escaped')", null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sector data fetch failed", e)
            } finally {
                sectorFetchInProgress = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // STATUS BAR
    // ═══════════════════════════════════════════════════════

    private fun refreshStatusBar() {
        isServiceRunning = config.serviceRunning

        if (isServiceRunning) {
            btnMonitor.text = "⏹  Stop Monitoring"
            btnMonitor.setBackgroundColor(0xFFdc2626.toInt())
        } else {
            btnMonitor.text = "▶  Start Monitoring"
            btnMonitor.setBackgroundColor(0xFF059669.toInt())
        }

        // Discovery scan status takes priority when running
        val holder = ScanServiceResultHolder
        if (holder.isDiscoveryRunning && holder.discoveryStatusText.isNotEmpty()) {
            txtStatus.text = holder.discoveryStatusText
            txtStatus.setTextColor(0xFF2563eb.toInt()) // blue for discovery
            return
        }

        // Otherwise show portfolio monitoring status
        if (isServiceRunning) {
            val lastScan = config.lastPortfolioScan
            val timeStr = if (lastScan > 0)
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastScan)) else "—"
            // Logo ImageView sits immediately to the left, so no brand prefix needed.
            txtStatus.text = "Monitoring · Last: $timeStr"
            txtStatus.setTextColor(0xFF059669.toInt())
        } else {
            // Show cached discovery info if available
            val cached = holder.lastDiscoveryResult
            if (cached != null && cached.isComplete) {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cached.timestamp))
                txtStatus.text = "${cached.market}: ${cached.allStocks.size} stocks · $timeStr"
                txtStatus.setTextColor(0xFF64748b.toInt()) // grey — stale data indicator
            } else {
                txtStatus.text = "Ready"
                txtStatus.setTextColor(0xFF0f172a.toInt())
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use OnBackPressedCallback", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        // If modal is open in WebView, close it instead of exiting
        webView.evaluateJavascript(
            "if(document.getElementById('modal').classList.contains('show')){closeDetail();true}else{false}",
            { result ->
                if (result == "false" || result == "null") {
                    super.onBackPressed()
                }
            }
        )
    }
}

/**
 * Static holder for discovery results — allows MainActivity to read
 * ScanService's results without binding.
 * In production, use a ViewModel + LiveData or Room DB instead.
 */
object ScanServiceResultHolder {
    @Volatile var lastDiscoveryResult: DiscoveryScanResult? = null
    @Volatile var lastZerodhaResult: com.signalscope.app.data.ScanResult? = null

    // Polling-friendly state set by ScanService — no broadcasts needed
    @Volatile var isDiscoveryRunning = false
    @Volatile var discoveryProgress = 0
    @Volatile var discoveryTotal = 0
    @Volatile var discoveryMarket = ""
    @Volatile var discoveryStatusText = ""
    /** Incremented each time the service updates results — UI polls for changes */
    @Volatile var discoveryVersion = 0L
    @Volatile var portfolioVersion = 0L
}
