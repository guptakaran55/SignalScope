# SignalScope Android — Background Portfolio Monitor

A native Android app that continuously scans your Angel One portfolio holdings
and sends push notifications when sell signals are detected.

## What It Does

- **Background scanning**: Runs as an Android foreground service, scanning your
  portfolio every 15 minutes (configurable)
- **Push notifications**: Alerts you when:
  - A held stock hits STRONG SELL (score ≥ 65)
  - A held stock hits MODERATE SELL (score ≥ 45)
  - MACD momentum flips bearish (SELL FLIP)
  - Price drops below SMA(200) (trend break)
  - Large unrealized gain + sell pressure (book profit signal)
  - Consecutive price decline with accelerating loss
- **Survives reboot**: Automatically restarts after phone restart
- **Battery efficient**: Only scans during Indian market hours by default
  (9:15 AM – 3:30 PM IST, weekdays)

## Technical Analysis Engine

All 6 indicators from the Python version are ported to Kotlin:

1. **SMA Trend** (25 pts) — Price vs SMA(200) or SMA(50) fallback
2. **MACD Inflection** (43 pts) — Slope derivatives, Golden Buy detection
3. **RSI(14)** (20 pts) — Graduated scoring, peak at RSI 35
4. **Bollinger Bands** (15 pts) — Mean reversion + lower band touch
5. **ADX + Directional Index** (20 pts) — Trend strength with bull/bear check
6. **OBV** (5 pts) — Volume confirmation
7. **EMA(21) Proximity** (10 pts) — Entry quality gauge

Plus the full sell scoring system (max ~108 pts) with structural gates.

## Setup

### Prerequisites
- Android Studio Hedgehog (2023.1) or later
- Android device running Android 8.0 (API 26) or higher
- Angel One SmartAPI credentials

### Build & Install

1. Open the project in Android Studio:
   ```
   File → Open → select the SignalScopeAndroid folder
   ```

2. Wait for Gradle sync to complete

3. Connect your Android phone via USB (enable Developer Mode + USB Debugging)

4. Click **Run** (▶) or:
   ```
   ./gradlew installDebug
   ```

5. Open the app → tap **⚙ Settings** → enter your Angel One credentials:
   - **API Key**: From your Angel One SmartAPI dashboard
   - **Client ID**: Your Angel One login ID
   - **Password**: Your Angel One password
   - **TOTP Secret**: The base32 secret from your authenticator app setup
     (the same value as ANGEL_TOTP_TOKEN in your .env file)

6. Tap **Test Connection** to verify

7. Go back to the main screen → tap **▶ Start Monitoring**

8. The app will ask to disable battery optimization — allow it for reliable
   background scanning

### Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Scan interval | 15 min | How often to check portfolio |
| Sell alert threshold | 45 pts | Minimum sell score for notifications |
| Strong sell threshold | 65 pts | Score for high-urgency alerts |
| Market hours only | On | Skip scanning on weekends/off-hours |
| Vibrate on alerts | On | Phone vibration for sell alerts |

## Architecture

```
com.signalscope.app/
├── data/
│   ├── Models.kt          — Data classes (StockAnalysis, alerts, etc.)
│   └── ConfigManager.kt   — Encrypted credential storage
├── network/
│   └── AngelOneClient.kt  — Angel One SmartAPI client (auth + data)
├── service/
│   ├── ScanService.kt     — Foreground service (scan loop + notifications)
│   └── BootReceiver.kt    — Restart after reboot
├── ui/
│   ├── MainActivity.kt    — Dashboard (status, alerts, holdings)
│   ├── SettingsActivity.kt— Credential entry + config
│   ├── AlertAdapter.kt    — Alert list renderer
│   └── HoldingAdapter.kt  — Holding list renderer
└── util/
    ├── Indicators.kt      — All technical indicators (SMA, RSI, MACD, etc.)
    ├── StockAnalyzer.kt   — Buy/sell scoring engine
    └── TOTP.kt            — TOTP generator (replaces pyotp)
```

## How Scanning Works

1. Service wakes up every N minutes
2. Checks if within market hours (configurable)
3. Generates TOTP → logs into Angel One SmartAPI
4. Fetches portfolio holdings
5. For each holding: fetches 730 days of candle data → runs full analysis
6. Compares sell scores against thresholds
7. Sends push notification for each alert (with 30-min cooldown per symbol)
8. Updates the persistent notification with scan status

## Differences from Python Version

- **Scope**: Only scans your portfolio holdings (not all 500 stocks).
  This keeps scanning fast (~2-3 min for 20 holdings vs 15-30 min for 500)
- **No dashboard charts**: The mobile app focuses on alerts. For the full
  visual dashboard, use the Python version on your laptop
- **No NASDAQ/Zerodha**: Angel One only (as requested)
- **Sell-score focused**: The mobile app emphasizes sell signals since you
  want to know when held stocks are declining

## Troubleshooting

**"Not connected" after entering credentials**
→ Double-check your TOTP secret. It should be the base32 string, not the
6-digit code. Same value as your ANGEL_TOTP_TOKEN env var.

**Notifications not appearing**
→ Check: Settings → Apps → SignalScope → Notifications → ensure both
channels ("Scan Status" and "Stock Alerts") are enabled.

**Scanning stops after a while**
→ Your phone's battery optimization is killing the service. Go to
Settings → Battery → SignalScope → set to "Unrestricted" or "No restrictions".
On Xiaomi/Samsung/OnePlus, also check vendor-specific battery settings.

**Rate limited by Angel One**
→ The app uses 0.5s pacing between API calls. If you get rate limited,
increase the scan interval to 30 minutes in Settings.
