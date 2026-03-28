package com.signalscope.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.DiscoveryScanResult
import com.signalscope.app.data.StockAnalysis
import com.signalscope.app.service.ScanService
import com.signalscope.app.network.YahooFinanceClient
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
    private var boundService: ScanService? = null
    private var isBound = false

    // Service connection for reading scan results directly
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // ScanService doesn't use binding currently — we use broadcasts instead
            // If you add a Binder later, connect here
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScanService.BROADCAST_SCAN_UPDATE -> {
                    // Portfolio scan completed — push holdings to WebView + refresh
                    refreshStatusBar()
                    pushPortfolioResultsToWebView()
                }
                ScanService.BROADCAST_DISCOVERY_UPDATE -> {
                    val progress = intent.getIntExtra(ScanService.EXTRA_DISCOVERY_PROGRESS, -1)
                    val total = intent.getIntExtra(ScanService.EXTRA_DISCOVERY_TOTAL, -1)
                    val market = intent.getStringExtra(ScanService.EXTRA_DISCOVERY_MARKET) ?: ""

                    if (progress < 0) {
                        // Scan complete — push final results to WebView
                        pushDiscoveryResultsToWebView()
                        webView.evaluateJavascript("window.scanComplete()", null)
                        btnStop.visibility = View.GONE
                        refreshStatusBar()
                    } else {
                        // In-progress — push results + update progress on every broadcast
                        pushDiscoveryResultsToWebView()
                        webView.evaluateJavascript(
                            "window.updateProgress($progress, $total, '${market.replace("'", "\\'")}')", null
                        )
                    }
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
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ScanService.BROADCAST_SCAN_UPDATE)
            addAction(ScanService.BROADCAST_DISCOVERY_UPDATE)
        }
        registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        isServiceRunning = config.serviceRunning
        refreshStatusBar()

        // If there are existing results, push them to WebView
        pushDiscoveryResultsToWebView()
        pushPortfolioResultsToWebView()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════
    // LAYOUT (programmatic — no XML dependency)
    // ═══════════════════════════════════════════════════════

    private fun buildLayout() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0a0f1e.toInt())
        }

        // ── Top bar: status + controls ──
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF0f1629.toInt())
        }

        // Status row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "S"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF059669.toInt())
            val size = dp(26)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            setPadding(0, 0, 0, 0)
        }
        statusRow.addView(logo)

        txtStatus = TextView(this).apply {
            text = "SignalScope"
            textSize = 14f
            setTextColor(0xFFf1f5f9.toInt())
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
            setBackgroundColor(0xFF1e3a5f.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) }
        }
        discRow.addView(btnNifty)

        btnNasdaq = MaterialButton(this).apply {
            text = "🇺🇸 NASDAQ 100"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFF1e3a5f.toInt())
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
            setBackgroundColor(0xFF0a0f1e.toInt())
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
            allowFileAccess = true
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
        fun getLastScanTime(): String {
            val ts = config.lastPortfolioScan
            return if (ts > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)) else "Never"
        }
    }

    // ═══════════════════════════════════════════════════════
    // BUTTON HANDLERS
    // ═══════════════════════════════════════════════════════

    private fun setupButtons() {
        btnMonitor.setOnClickListener {
            if (!config.hasCredentials && !config.isZerodhaConnected) {
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
                "isScanning" to !result.isComplete,
                "progress" to if (!result.isComplete) mapOf(
                    "current" to result.totalScanned,
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
        val zerodha = ScanServiceResultHolder.lastZerodhaResult
        val angel = ScanServiceResultHolder.lastAngelResult
        // Combine holdings from both sources
        val allHoldings = mutableListOf<Map<String, Any?>>()

        fun addHoldings(result: com.signalscope.app.data.ScanResult?, source: String) {
            result?.holdings?.forEach { h ->
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
                    "source" to source,
                    "verdict" to h.verdict,
                    "buyScore" to (a?.buyScore ?: 0),
                    "sellScore" to (a?.sellScore ?: 0),
                    "macdPhase" to (a?.macdPhase ?: "—"),
                    "macdSlope" to (a?.macdSlope ?: 0.0),
                    "buySignal" to (a?.buySignal ?: "—"),
                    "sellSignal" to (a?.sellSignal ?: "—"),
                    "hasAnalysis" to (a != null)
                ))
            }
        }

        addHoldings(zerodha, "zerodha")
        addHoldings(angel, "angel")

        if (allHoldings.isEmpty()) return

        try {
            val totalInvested = allHoldings.sumOf { (it["invested"] as? Double) ?: 0.0 }
            val totalCurrent = allHoldings.sumOf { (it["currentVal"] as? Double) ?: 0.0 }
            val totalPnl = totalCurrent - totalInvested
            val dayPnl = allHoldings.sumOf { (it["dayChange"] as? Double) ?: 0.0 }
            val sellAlerts = allHoldings.count {
                ((it["sellScore"] as? Int) ?: 0) >= 45
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

                            sectors.add(mapOf(
                                "name" to name,
                                "index" to index,
                                "symbol" to symbol,
                                "price" to latest.close,
                                "dayChange" to dayChange,
                                "periodChange" to periodChange,
                                "high" to high,
                                "low" to low
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
            val lastScan = config.lastPortfolioScan
            val timeStr = if (lastScan > 0)
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastScan)) else "—"
            txtStatus.text = "SignalScope · Monitoring · Last: $timeStr"
            txtStatus.setTextColor(0xFF059669.toInt())
        } else {
            btnMonitor.text = "▶  Start Monitoring"
            btnMonitor.setBackgroundColor(0xFF059669.toInt())
            txtStatus.text = "SignalScope"
            txtStatus.setTextColor(0xFFf1f5f9.toInt())
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
    @Volatile var lastAngelResult: com.signalscope.app.data.ScanResult? = null
}
