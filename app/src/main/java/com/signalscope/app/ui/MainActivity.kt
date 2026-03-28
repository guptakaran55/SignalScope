package com.signalscope.app.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.signalscope.app.R
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.DiscoveryScanResult
import com.signalscope.app.databinding.ActivityMainBinding
import com.signalscope.app.service.ScanService
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: ConfigManager
    private val alertAdapter = AlertAdapter()
    private val holdingAdapter = HoldingAdapter()
    private val discoveryAdapter = DiscoveryAdapter()

    private var isServiceRunning = false

    /** Which tab is active: "portfolio" or "discovery" */
    private var activeTab = "portfolio"

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScanService.BROADCAST_SCAN_UPDATE -> refreshUI()
                ScanService.BROADCAST_DISCOVERY_UPDATE -> {
                    val progress = intent.getIntExtra(ScanService.EXTRA_DISCOVERY_PROGRESS, -1)
                    val total = intent.getIntExtra(ScanService.EXTRA_DISCOVERY_TOTAL, -1)
                    val market = intent.getStringExtra(ScanService.EXTRA_DISCOVERY_MARKET) ?: ""
                    onDiscoveryProgress(market, progress, total)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigManager(this)
        setupUI()
        requestNotificationPermission()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ScanService.BROADCAST_SCAN_UPDATE)
            addAction(ScanService.BROADCAST_DISCOVERY_UPDATE)
        }
        registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        isServiceRunning = config.serviceRunning
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    private fun setupUI() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Portfolio monitoring toggle
        binding.btnScanToggle.setOnClickListener {
            if (!config.hasCredentials && !config.isZerodhaConnected) {
                Toast.makeText(this, "Configure credentials in Settings first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            if (isServiceRunning) stopScanService() else {
                startScanService()
                requestBatteryOptimizationExemption()
            }
        }

        // ── Discovery scan buttons (work anytime, outside market hours) ──
        binding.btnDiscoveryNifty.setOnClickListener {
            ensureServiceRunning()
            val intent = ScanService.createIntent(this, ScanService.ACTION_DISCOVERY_NIFTY500)
            startForegroundService(intent)
            Toast.makeText(this, "NIFTY discovery scan starting...", Toast.LENGTH_SHORT).show()
            activeTab = "discovery"
            refreshUI()
        }

        binding.btnDiscoveryNasdaq.setOnClickListener {
            ensureServiceRunning()
            val intent = ScanService.createIntent(this, ScanService.ACTION_DISCOVERY_NASDAQ100)
            startForegroundService(intent)
            Toast.makeText(this, "NASDAQ 100 discovery scan starting...", Toast.LENGTH_SHORT).show()
            activeTab = "discovery"
            refreshUI()
        }

        binding.btnDiscoveryStop.setOnClickListener {
            val intent = ScanService.createIntent(this, ScanService.ACTION_DISCOVERY_STOP)
            startService(intent)
            Toast.makeText(this, "Discovery scan stopping...", Toast.LENGTH_SHORT).show()
        }

        // Tab switching
        binding.tabPortfolio.setOnClickListener { activeTab = "portfolio"; refreshUI() }
        binding.tabDiscovery.setOnClickListener { activeTab = "discovery"; refreshUI() }

        // Swipe to refresh
        binding.swipeRefresh.setOnRefreshListener {
            if (isServiceRunning) {
                val intent = ScanService.createIntent(this, ScanService.ACTION_SCAN_NOW)
                startForegroundService(intent)
                Toast.makeText(this, "Scanning now...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Start monitoring first", Toast.LENGTH_SHORT).show()
            }
            binding.swipeRefresh.isRefreshing = false
        }

        // RecyclerViews
        binding.alertsList.layoutManager = LinearLayoutManager(this)
        binding.alertsList.adapter = alertAdapter
        binding.holdingsList.layoutManager = LinearLayoutManager(this)
        binding.holdingsList.adapter = holdingAdapter
        binding.discoveryList.layoutManager = LinearLayoutManager(this)
        binding.discoveryList.adapter = discoveryAdapter
    }

    private fun refreshUI() {
        isServiceRunning = config.serviceRunning

        // ── Toggle button ──
        if (isServiceRunning) {
            binding.btnScanToggle.text = "⏹  Stop Monitoring"
            binding.btnScanToggle.setBackgroundColor(0xFFdc2626.toInt())
        } else {
            binding.btnScanToggle.text = "▶  Start Monitoring"
            binding.btnScanToggle.setBackgroundColor(0xFF059669.toInt())
        }

        // ── Status ──
        val hasAnyCreds = config.hasCredentials || config.isZerodhaConnected
        if (hasAnyCreds) {
            if (isServiceRunning) {
                binding.statusDot.setBackgroundResource(R.drawable.dot_on)
                binding.txtStatus.text = "Monitoring active"
                binding.txtStatus.setTextColor(0xFF059669.toInt())
            } else {
                binding.statusDot.setBackgroundResource(R.drawable.dot_off)
                binding.txtStatus.text = "Monitoring stopped"
                binding.txtStatus.setTextColor(0xFF94a3b8.toInt())
            }
            val sources = mutableListOf<String>()
            if (config.hasCredentials) sources.add("Angel One: ${config.clientId}")
            if (config.isZerodhaConnected) sources.add("Zerodha: ${config.zerodhaUserName}")
            binding.txtScanInfo.text = sources.joinToString(" · ")
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.dot_off)
            binding.txtStatus.text = "Not configured"
            binding.txtStatus.setTextColor(0xFFdc2626.toInt())
            binding.txtScanInfo.text = "Tap ⚙ Settings to add credentials"
        }

        // ── Last scan time ──
        val lastScan = config.lastPortfolioScan
        binding.txtLastScan.text = if (lastScan > 0) {
            "Last: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastScan))}"
        } else "Never"

        // ── Tab highlighting ──
        binding.tabPortfolio.setBackgroundColor(
            if (activeTab == "portfolio") 0xFF1e293b.toInt() else 0xFF111827.toInt())
        binding.tabDiscovery.setBackgroundColor(
            if (activeTab == "discovery") 0xFF1e293b.toInt() else 0xFF111827.toInt())

        // ── Show/hide sections based on active tab ──
        val showPortfolio = activeTab == "portfolio"
        binding.portfolioSection.visibility = if (showPortfolio) View.VISIBLE else View.GONE
        binding.discoverySection.visibility = if (!showPortfolio) View.VISIBLE else View.GONE

        // Stats
        updateStatCard(binding.statHoldings, "HOLDINGS", "—", "portfolio stocks")
        updateStatCard(binding.statAlerts, "ALERTS", "0", "active")
        updateStatCard(binding.statBuyScore, "AVG BUY", "—", "score")
        updateStatCard(binding.statSellScore, "AVG SELL", "—", "score")

        // Empty states
        val hasAlerts = alertAdapter.currentList.isNotEmpty()
        binding.alertsList.visibility = if (hasAlerts) View.VISIBLE else View.GONE
        binding.txtNoAlerts.visibility = if (hasAlerts) View.GONE else View.VISIBLE

        val hasHoldings = holdingAdapter.currentList.isNotEmpty()
        binding.holdingsList.visibility = if (hasHoldings) View.VISIBLE else View.GONE
        binding.txtNoHoldings.visibility = if (hasHoldings) View.GONE else View.VISIBLE
    }

    private fun onDiscoveryProgress(market: String, progress: Int, total: Int) {
        if (progress < 0) {
            // Scan complete or stopped
            binding.discoveryProgress.visibility = View.GONE
            binding.btnDiscoveryStop.visibility = View.GONE
            refreshDiscoveryResults()
            return
        }

        binding.discoveryProgress.visibility = View.VISIBLE
        binding.btnDiscoveryStop.visibility = View.VISIBLE
        val pct = if (total > 0) progress * 100 / total else 0
        binding.discoveryProgressText.text = "$market: $progress/$total ($pct%)"
        binding.discoveryProgressBar.progress = pct

        // Update results in real-time as they come in
        refreshDiscoveryResults()
    }

    private fun refreshDiscoveryResults() {
        // Access service's lastDiscoveryResult
        // Since we can't bind to service directly here, we'll use a static holder
        // In production, use a ViewModel with LiveData or a bound service
        // For now, we broadcast the data or use a singleton
        // TODO: Use proper service binding or ViewModel pattern

        // Placeholder: show count in discovery header
        binding.discoveryInfo.text = "Scan results will appear here after discovery scan completes.\n" +
                "Discovery scans work anytime — not limited to market hours."
    }

    private fun updateStatCard(cardView: com.signalscope.app.databinding.StatCardBinding,
                               label: String, value: String, sub: String) {
        cardView.statLabel.text = label
        cardView.statValue.text = value
        cardView.statSub.text = sub
    }

    // ═══════════════════════════════════════════════════════
    // SERVICE CONTROL
    // ═══════════════════════════════════════════════════════

    private fun ensureServiceRunning() {
        if (!isServiceRunning) {
            val intent = ScanService.createIntent(this, ScanService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
            isServiceRunning = true
            config.serviceRunning = true
        }
    }

    private fun startScanService() {
        val intent = ScanService.createIntent(this, ScanService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        config.serviceRunning = true
        refreshUI()
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun stopScanService() {
        val intent = ScanService.createIntent(this, ScanService.ACTION_STOP)
        startService(intent)
        isServiceRunning = false
        config.serviceRunning = false
        refreshUI()
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
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
}
