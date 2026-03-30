package com.signalscope.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.ZerodhaClient
import kotlinx.coroutines.*

/**
 * Settings screen — enter Zerodha credentials, scan config, and AI settings.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Zerodha fields ──
    private lateinit var etZerodhaApiKey: EditText
    private lateinit var etZerodhaApiSecret: EditText
    private lateinit var btnZerodhaLogin: Button
    private lateinit var btnZerodhaToken: Button
    private lateinit var etZerodhaToken: EditText
    private lateinit var tvZerodhaStatus: TextView
    private lateinit var layoutZerodhaToken: View

    // ── Config fields ──
    private lateinit var spinnerInterval: Spinner
    private lateinit var switchMarketHours: Switch
    private lateinit var switchVibrate: Switch

    // ── LLM / AI fields ──
    private lateinit var etLlmApiKey: EditText
    private lateinit var spinnerLlmProvider: Spinner
    private lateinit var spinnerLlmModel: Spinner
    private lateinit var tvLlmStatus: TextView

    // ── Save ──
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigManager(this)
        setContentView(buildSettingsLayout())
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
                setTextColor(android.graphics.Color.BLACK)
                setHintTextColor(android.graphics.Color.parseColor("#94a3b8"))
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

        // ── AI / LLM section ──
        addSection("AI Stock Analysis (Optional)")

        inner.addView(TextView(this).apply {
            text = "Enables AI-powered pullback analysis and stock outlook summaries.\nUses Google News for headlines + your LLM API for summarization."
            textSize = 11f
            setPadding(0, 0, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        addLabel("API Key (OpenAI or Anthropic)")
        etLlmApiKey = addField("sk-... or sk-ant-...", isPassword = true)

        addLabel("Provider")
        spinnerLlmProvider = Spinner(this)
        val providers = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("OpenAI (ChatGPT)", "Anthropic (Claude)"))
        providers.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmProvider.adapter = providers
        inner.addView(spinnerLlmProvider)

        addLabel("Model")
        spinnerLlmModel = Spinner(this)
        val models = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("gpt-4o-mini (cheapest)", "gpt-4o", "claude-sonnet-4-20250514", "claude-haiku-4-20250414"))
        models.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmModel.adapter = models
        inner.addView(spinnerLlmModel)

        tvLlmStatus = addStatus()
        if (config.hasLlmCredentials) {
            tvLlmStatus.text = "✅ AI analysis enabled (${config.llmProvider} / ${config.llmModel})"
            tvLlmStatus.setTextColor(android.graphics.Color.parseColor("#059669"))
            tvLlmStatus.visibility = View.VISIBLE
        }

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
        etZerodhaApiKey.setText(config.zerodhaApiKey)
        etZerodhaApiSecret.setText(config.zerodhaApiSecret)

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

        // LLM settings
        etLlmApiKey.setText(config.openaiApiKey)
        spinnerLlmProvider.setSelection(if (config.llmProvider == "anthropic") 1 else 0)
        val modelIdx = when (config.llmModel) {
            "gpt-4o-mini" -> 0; "gpt-4o" -> 1
            "claude-sonnet-4-20250514" -> 2; "claude-haiku-4-20250414" -> 3
            else -> 0
        }
        spinnerLlmModel.setSelection(modelIdx)

        // Show Zerodha connection status
        if (config.isZerodhaConnected) {
            tvZerodhaStatus.text = "✅ Connected as ${config.zerodhaUserName}"
            tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#059669"))
            tvZerodhaStatus.visibility = View.VISIBLE
        }
    }

    private fun saveSettings() {
        // Zerodha
        config.zerodhaApiKey = etZerodhaApiKey.text.toString().trim()
        config.zerodhaApiSecret = etZerodhaApiSecret.text.toString().trim()

        // Scan config
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

        // LLM settings
        config.openaiApiKey = etLlmApiKey.text.toString().trim()
        config.llmProvider = if (spinnerLlmProvider.selectedItemPosition == 1) "anthropic" else "openai"
        config.llmModel = when (spinnerLlmModel.selectedItemPosition) {
            0 -> "gpt-4o-mini"; 1 -> "gpt-4o"
            2 -> "claude-sonnet-4-20250514"; 3 -> "claude-haiku-4-20250414"
            else -> "gpt-4o-mini"
        }

        Toast.makeText(this, "✅ Settings saved", Toast.LENGTH_SHORT).show()
        finish()
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