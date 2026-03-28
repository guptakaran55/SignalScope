package com.signalscope.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.signalscope.app.R
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.AngelOneClient
import com.signalscope.app.network.ZerodhaClient
import kotlinx.coroutines.*

/**
 * Settings screen — enter credentials for Angel One and Zerodha.
 *
 * Field names match the original .env file exactly:
 *   ANGEL_API_KEY, ANGEL_CLIENT_ID, ANGEL_PASSWORD, ANGEL_TOTP_TOKEN
 *   ZERODHA_API_KEY, ZERODHA_API_SECRET
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Angel One fields ──
    private lateinit var etApiKey: EditText
    private lateinit var etClientId: EditText
    private lateinit var etPassword: EditText
    private lateinit var etTotpToken: EditText
    private lateinit var btnTestAngel: Button
    private lateinit var tvAngelStatus: TextView

    // ── Zerodha fields ──
    private lateinit var etZerodhaApiKey: EditText
    private lateinit var etZerodhaApiSecret: EditText
    private lateinit var btnZerodhaLogin: Button
    private lateinit var btnZerodhaToken: Button
    private lateinit var etZerodhaToken: EditText
    private lateinit var tvZerodhaStatus: TextView
    private lateinit var layoutZerodhaToken: View

    // ── Config fields ──
    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerInterval: Spinner
    private lateinit var switchMarketHours: Switch
    private lateinit var switchVibrate: Switch

    // ── Save ──
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use your existing activity_settings layout or create a simple one programmatically
        setContentView(buildSettingsLayout())

        config = ConfigManager(this)
        loadCurrentValues()
    }

    /**
     * Builds the settings UI programmatically so you don't need to modify XML layout files.
     * In your real project you can replace this with your activity_settings.xml layout.
     */
    private fun buildSettingsLayout(): View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val scroll = android.widget.ScrollView(this)
        val inner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
        }

        fun addSection(title: String) {
            inner.addView(TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 32, 0, 8)
                setTextColor(android.graphics.Color.parseColor("#059669"))
            })
        }

        fun addLabel(text: String) {
            inner.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setPadding(0, 16, 0, 4)
                setTextColor(android.graphics.Color.parseColor("#475569"))
            })
        }

        fun addField(hint: String, isPassword: Boolean = false): EditText {
            val et = EditText(this).apply {
                this.hint = hint
                if (isPassword) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(24, 16, 24, 16)
                setBackgroundResource(android.R.drawable.edit_text)
            }
            inner.addView(et)
            return et
        }

        fun addStatus(): TextView {
            val tv = TextView(this).apply {
                textSize = 12f
                setPadding(0, 8, 0, 0)
                visibility = View.GONE
            }
            inner.addView(tv)
            return tv
        }

        fun addButton(label: String): Button {
            val btn = Button(this).apply {
                text = label
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 8
                layoutParams = lp
            }
            inner.addView(btn)
            return btn
        }

        // ── Angel One section ──
        addSection("Angel One Credentials")
        addLabel("API Key (ANGEL_API_KEY)")
        etApiKey = addField("Enter Angel One API Key")
        addLabel("Client ID (ANGEL_CLIENT_ID)")
        etClientId = addField("Enter Client ID")
        addLabel("Password (ANGEL_PASSWORD)")
        etPassword = addField("Enter password", isPassword = true)
        addLabel("TOTP Secret (ANGEL_TOTP_TOKEN)")
        etTotpToken = addField("Base32 secret from authenticator setup")

        inner.addView(TextView(this).apply {
            text = "⚠ TOTP Secret is the base32 key (e.g. JBSWY3DP...) — NOT the 6-digit code"
            textSize = 11f
            setPadding(0, 4, 0, 0)
            setTextColor(android.graphics.Color.parseColor("#d97706"))
        })

        btnTestAngel = addButton("🔌 Test Angel One Connection")
        tvAngelStatus = addStatus()

        // ── Zerodha section ──
        addSection("Zerodha Credentials")
        addLabel("API Key (ZERODHA_API_KEY)")
        etZerodhaApiKey = addField("Enter Zerodha API Key")
        addLabel("API Secret (ZERODHA_API_SECRET)")
        etZerodhaApiSecret = addField("Enter Zerodha API Secret", isPassword = true)

        inner.addView(TextView(this).apply {
            text = "After saving, use 'Login with Zerodha' to connect your account daily"
            textSize = 11f
            setPadding(0, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        btnZerodhaLogin = addButton("🔗 Open Zerodha Login")
        tvZerodhaStatus = addStatus()

        // Token paste section
        layoutZerodhaToken = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addLabel("Paste redirect URL or request_token from Zerodha:")
        etZerodhaToken = addField("Paste full URL or token here")
        btnZerodhaToken = addButton("✓ Submit Token")
        inner.addView(layoutZerodhaToken)

        // ── Scan config section ──
        addSection("Scan Configuration")

        addLabel("Scan holdings from:")
        spinnerSource = Spinner(this)
        val sources = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("Both Angel One & Zerodha", "Angel One only", "Zerodha only"))
        sources.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSource.adapter = sources
        inner.addView(spinnerSource)

        addLabel("Scan interval:")
        spinnerInterval = Spinner(this)
        val intervals = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("5 minutes", "10 minutes", "15 minutes", "30 minutes", "60 minutes"))
        intervals.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervals
        inner.addView(spinnerInterval)

        switchMarketHours = Switch(this).apply {
            text = "Scan during market hours only (9:15–15:30 IST)"
            setPadding(0, 16, 0, 0)
        }
        inner.addView(switchMarketHours)

        switchVibrate = Switch(this).apply {
            text = "Vibrate on alerts"
            setPadding(0, 8, 0, 0)
        }
        inner.addView(switchVibrate)

        // ── Save button ──
        btnSave = Button(this).apply {
            text = "💾 Save Settings"
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 32
            layoutParams = lp
            setBackgroundColor(android.graphics.Color.parseColor("#059669"))
            setTextColor(android.graphics.Color.WHITE)
        }
        inner.addView(btnSave)

        scroll.addView(inner)
        layout.addView(scroll)

        setupListeners()
        return layout
    }

    private fun setupListeners() {
        btnSave.setOnClickListener { saveSettings() }

        btnTestAngel.setOnClickListener { testAngelConnection() }

        btnZerodhaLogin.setOnClickListener {
            // Save API key first so login URL is correct
            config.zerodhaApiKey = etZerodhaApiKey.text.toString().trim()
            config.zerodhaApiSecret = etZerodhaApiSecret.text.toString().trim()

            val url = config.zerodhaLoginUrl
            if (url.isBlank()) {
                tvZerodhaStatus.text = "❌ Enter Zerodha API Key first"
                tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                tvZerodhaStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Open Zerodha login in browser
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

            // Show token input
            layoutZerodhaToken.visibility = View.VISIBLE
            tvZerodhaStatus.text = "After login, copy the redirect URL and paste below"
            tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#d97706"))
            tvZerodhaStatus.visibility = View.VISIBLE
        }

        btnZerodhaToken.setOnClickListener { submitZerodhaToken() }
    }

    private fun loadCurrentValues() {
        etApiKey.setText(config.apiKey)
        etClientId.setText(config.clientId)
        etPassword.setText(config.password)
        etTotpToken.setText(config.totpToken)
        etZerodhaApiKey.setText(config.zerodhaApiKey)
        etZerodhaApiSecret.setText(config.zerodhaApiSecret)

        // Source spinner
        spinnerSource.setSelection(when (config.portfolioSource) {
            "angel"   -> 1
            "zerodha" -> 2
            else      -> 0
        })

        // Interval spinner
        spinnerInterval.setSelection(when (config.portfolioScanIntervalMin) {
            5  -> 0
            10 -> 1
            15 -> 2
            30 -> 3
            60 -> 4
            else -> 2
        })

        switchMarketHours.isChecked = config.scanDuringMarketHoursOnly
        switchVibrate.isChecked = config.vibrateOnAlerts

        // Show Zerodha connection status
        if (config.isZerodhaConnected) {
            tvZerodhaStatus.text = "✅ Connected as ${config.zerodhaUserName}"
            tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#059669"))
            tvZerodhaStatus.visibility = View.VISIBLE
        }
    }

    private fun saveSettings() {
        // Angel One
        config.apiKey = etApiKey.text.toString().trim()
        config.clientId = etClientId.text.toString().trim()
        config.password = etPassword.text.toString().trim()
        config.totpToken = etTotpToken.text.toString().trim()

        // Zerodha
        config.zerodhaApiKey = etZerodhaApiKey.text.toString().trim()
        config.zerodhaApiSecret = etZerodhaApiSecret.text.toString().trim()

        // Scan config
        config.portfolioSource = when (spinnerSource.selectedItemPosition) {
            1    -> "angel"
            2    -> "zerodha"
            else -> "both"
        }
        config.portfolioScanIntervalMin = when (spinnerInterval.selectedItemPosition) {
            0    -> 5
            1    -> 10
            2    -> 15
            3    -> 30
            4    -> 60
            else -> 15
        }
        config.scanDuringMarketHoursOnly = switchMarketHours.isChecked
        config.vibrateOnAlerts = switchVibrate.isChecked

        Toast.makeText(this, "✅ Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testAngelConnection() {
        // Save fields first
        config.apiKey = etApiKey.text.toString().trim()
        config.clientId = etClientId.text.toString().trim()
        config.password = etPassword.text.toString().trim()
        config.totpToken = etTotpToken.text.toString().trim()

        if (!config.hasAngelCredentials) {
            tvAngelStatus.text = "❌ Fill in all Angel One fields first"
            tvAngelStatus.setTextColor(android.graphics.Color.parseColor("#dc2626"))
            tvAngelStatus.visibility = View.VISIBLE
            return
        }

        btnTestAngel.isEnabled = false
        tvAngelStatus.text = "Testing connection..."
        tvAngelStatus.setTextColor(android.graphics.Color.parseColor("#475569"))
        tvAngelStatus.visibility = View.VISIBLE

        scope.launch {
            val client = AngelOneClient(config)
            val result = withContext(Dispatchers.IO) { client.login() }

            withContext(Dispatchers.Main) {
                btnTestAngel.isEnabled = true
                when (result) {
                    is AngelOneClient.AuthResult.Success -> {
                        tvAngelStatus.text = "✅ Connected to Angel One"
                        tvAngelStatus.setTextColor(android.graphics.Color.parseColor("#059669"))
                    }
                    is AngelOneClient.AuthResult.Failure -> {
                        tvAngelStatus.text = "❌ ${result.message}"
                        tvAngelStatus.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                    }
                }
            }
        }
    }

    private fun submitZerodhaToken() {
        var token = etZerodhaToken.text.toString().trim()
        if (token.isBlank()) return

        // Extract request_token from full URL if pasted
        val match = Regex("request_token=([^&]+)").find(token)
        if (match != null) token = match.groupValues[1]

        btnZerodhaToken.isEnabled = false
        tvZerodhaStatus.text = "Connecting to Zerodha..."
        tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#475569"))

        scope.launch {
            val client = ZerodhaClient(config)
            val result = withContext(Dispatchers.IO) { client.exchangeRequestToken(token) }

            withContext(Dispatchers.Main) {
                btnZerodhaToken.isEnabled = true
                when (result) {
                    is ZerodhaClient.ZerodhaAuthResult.Success -> {
                        tvZerodhaStatus.text = "✅ Zerodha connected — ${result.userName}"
                        tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#059669"))
                        layoutZerodhaToken.visibility = View.GONE
                        etZerodhaToken.setText("")
                    }
                    is ZerodhaClient.ZerodhaAuthResult.Failure -> {
                        tvZerodhaStatus.text = "❌ ${result.message}"
                        tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}