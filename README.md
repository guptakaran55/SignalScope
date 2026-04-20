# SignalScope Android — Background Portfolio Monitor

A native Android app that continuously scans your Zerodha portfolio holdings
and sends push notifications when sell signals are detected.

## What It Does

- **Background scanning**: Runs as an Android foreground service, scanning your
  portfolio every 15 minutes (configurable)
- **Push notifications**: Alerts you when:
  - A held stock hits STRONG SELL (score >= 65)
  - A held stock hits MODERATE SELL (score >= 45)
  - MACD momentum flips bearish (SELL FLIP)
  - Price drops below SMA(200) (trend break)
  - Large unrealized gain + sell pressure (book profit signal)
  - Consecutive price decline with accelerating loss
- **Survives reboot**: Automatically restarts after phone restart
- **Battery efficient**: Only scans during Indian market hours by default
  (9:15 AM - 3:30 PM IST, weekdays)

## Technical Analysis Engine

SignalScope uses a **dual-intent scoring system** with three independent scorecards: **BUY**, **PROFIT BOOKING**, and **CAPITAL PROTECTION**. This avoids false signals by never mixing momentum (MACD) with structural breaks (SMA200).

### BUY SCORE (max ~138 pts)

Identifies accumulation phases — when to enter:

1. **SMA Trend** (25 pts) — Price > SMA(200) or SMA(50) fallback
2. **MACD Inflection** (~43 pts max) — Slope crossing, Golden Buy detection
   - Golden Buy: MACD at 60th %ile + slope ≤ 0.2 + SMA200 uptrend = +bonus
   - BUY FLIP: slope crosses zero upward after 2 days of decline
   - EARLY BUY: slope still negative but acceleration is positive
3. **RSI(14)** (~20 pts) — Graduated scoring, peak at RSI 25–35 (oversold)
   - Bonus: RSI flip (turns upward while oversold)
4. **Bollinger Bands** (~15 pts) — Mean reversion
   - Base: price below mid-band
   - Bonus: touched lower band in last 5 days
5. **ADX + Directional Index** (~20 pts) — Trend strength
   - Price > SMA200 (uptrend gate)
   - ADX > 25 (strong trend) gives base score
   - ADX > 35 (very strong) gives bonus
6. **OBV** (5 pts) — Volume confirmation
   - Current OBV > 5-day OBV AND > 20-day OBV
7. **EMA(21) Proximity** (~10 pts) — Entry quality
   - Price < 3% below EMA21: max points
   - Degrades at +2%, +4%, +7% distance

**Buy Signals:**
- STRONG BUY: ≥ 75 pts
- MODERATE BUY: ≥ 60 pts

### PROFIT BOOKING SCORE (max ~58 pts)

Identifies exit at MACD momentum peaks — when to take profits:

1. **MACD Momentum** (~30 pts) — Mutually exclusive (take highest)
   - SELL FLIP: slope crosses zero downward = max pts
   - EARLY SELL: slope still positive but acceleration is negative
   - MACD zero-cross down + acceleration < 0
   - Bonus: MACD at 90th percentile (overstretched)
2. **RSI Overbought** (~18 pts) — Graduated tiers
   - RSI > 70: extreme (max points)
   - RSI ≥ 60: overbought
   - RSI ≥ 50: mild
   - RSI ≥ 40: noise
   - Bonus: RSI flip (turns downward while overbought)
3. **Bollinger Bands Stretch** (~10 pts)
   - Price ≥ upper band: max pts
   - Touched upper band in last 5 days: lower score

**Gate:** Profit score only fires if **price > SMA200** (uptrend intact — don't exit a bull market prematurely)

**Signal:** Fires when score ≥ threshold → "BOOK PROFIT" (MACD slope at peak, sell 50–70%)

### CAPITAL PROTECTION SCORE (max ~58 pts)

Identifies trend breaks — when to cut losses:

1. **SMA200 Structural Break** (~20 pts) — Primary signal
   - Price < SMA200: base score
   - Bonus: SMA200 slope < 0 (confirms downtrend)
2. **ADX Bearish** (~18 pts) — Mutually exclusive
   - ADX > 35 + -DI > +DI (strong downtrend): max pts
   - ADX > 25 + -DI > +DI (weak downtrend): lower score
3. **OBV Distribution** (~10 pts) — Volume confirms exit
   - Current OBV < 5-day OBV AND < 20-day OBV
4. **MACD Structural** (~10 pts) — Momentum confirmation
   - MACD crosses zero downward

**Gate:** Capital protection activates if **price < SMA200 OR ADX bearish signals present**

**Signal:** Fires when score ≥ threshold → "PROTECT CAPITAL" (structural damage, exit entire position)

### DUAL-INTENT VERDICT

The **sellIntent** field merges the two sub-scores:

- **STRONG EXIT**: BOTH profit ≥ threshold AND protect ≥ threshold
  - Meaning: At MACD peak (take profits) AND trend broken (protect capital)
  - Action: Sell all immediately
- **BOOK PROFIT**: Profit ≥ threshold BUT protect < threshold
  - Meaning: At MACD peak but still in uptrend
  - Action: Sell 50–70%, trail the rest
- **PROTECT CAPITAL**: Protect ≥ threshold BUT profit < threshold
  - Meaning: Trend broken, exit before further damage
  - Action: Sell entire position
- **HOLD**: Both < threshold
  - No actionable signal

### Risk & Reward Metrics (Informational, Non-Scored)

These are calculated but not part of the scoring system — they inform position sizing:

1. **Support/Resistance** — Adaptive lookback using pivot density + ATR clustering
   - Instead of fixed 60-day window, the algorithm detects natural cycle length
   - Finds levels like BHARTIARTL's ₹2050 from 5 months ago, not just recent noise
2. **Risk** (in currency) — Gap from current price to support
3. **Reward** (in currency) — Gap from current price to resistance
4. **R:R Ratio** — reward / risk (e.g., 2.5 means 2.5:1 risk-reward)
5. **ATR(14)** — Average True Range, volatility measure
6. **Risk in ATRs** — risk gap / ATR (how many volatility units to support)
7. **Position Size** — shares you can buy with ₹10,000 risk budget
8. **Capital Needed** — total investment to buy that position
9. **Potential Profit** — profit if price hits resistance with that position
10. **ROC %** — Return on Capital, profit as % of capital invested
11. **ROC 3-Day** — 3-day price rate of change (momentum indicator, not scored)
12. **Price Velocity** — 1-day % change
13. **Price Acceleration** — velocity change day-over-day
14. **Up Days** — consecutive days price closed higher

### VALUE SCORE (0–100, Discovery tab only)

For each stock Yahoo Finance can fetch fundamentals, a separate VALUE SCORE rates it as a value catch:

**Scoring Categories:**

1. **Valuation Multiples** (max 35 pts)
   - P/E vs sector median: cheaper than peers?
   - P/E historically low: < 15 proxy for 5Y-average check
   - Price-to-Book (PB): low PB = cheap book value
   - EV/EBITDA: low relative to industry
2. **Financial Safety** (max 30 pts)
   - Debt-to-Equity: low debt = safe
   - ROCE (using ROE as proxy from Yahoo): high ROCE = quality business
   - CFO > Net Income: high-quality earnings (cash, not accounting tricks)
3. **Shareholder Yield** (max 15 pts)
   - Dividend yield: high = passive income
   - Buyback detection: (planned for future — compares shares outstanding over time)
4. **Price Discount** (max 12 pts)
   - % from 52-week low: close to low = bargain entry
5. **News Sentiment** (±8 pts, reserved)
   - Planned: LLM overlay to detect bullish/bearish articles

**Thresholds:**

- **DEEP VALUE**: ≥ 70 pts — All 5 categories screaming cheap
- **MODERATE VALUE**: ≥ 50 pts — Several metrics attractive
- **MILD VALUE**: ≥ 30 pts — One or two factors
- **NOT ATTRACTIVE**: < 30 pts — Expensive or mixed signals

Each stock shows both its **BUY SCORE** (momentum/technical) and **VALUE SCORE** (fundamentals). Buy Score drives portfolio alerts; Value Score helps discovery scanning find quality setups at cheap prices.

## Setup

### Prerequisites
- Android Studio Hedgehog (2023.1) or later
- Android device running Android 8.0 (API 26) or higher
- Zerodha Kite credentials

### Build & Install

1. Open the project in Android Studio:
   ```
   File -> Open -> select the SignalScopeAndroid folder
   ```

2. Wait for Gradle sync to complete

3. Connect your Android phone via USB (enable Developer Mode + USB Debugging)

4. Click **Run** or:
   ```
   ./gradlew installDebug
   ```

5. Open the app -> tap **Settings** -> enter your Zerodha credentials:
   - **API Key**: From your Zerodha Kite Connect dashboard
   - **API Secret**: Your Zerodha API secret
   - **Request Token**: Generated during Zerodha login flow

6. Tap **Test Connection** to verify

7. Go back to the main screen -> tap **Start Monitoring**

8. The app will ask to disable battery optimization — allow it for reliable
   background scanning

9. You'll be prompted for a **master password** on first launch. This is not stored
   in the app — it's fetched from a secure remote source (GitHub Gist) and hashed
   with SHA-256 for comparison. This allows you to revoke access to all users by
   changing the master password centrally

### Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Scan interval | 15 min | How often to check portfolio |
| Sell alert threshold | 45 pts | Minimum sell score for notifications |
| Strong sell threshold | 65 pts | Score for high-urgency alerts |
| Market hours only | On | Skip scanning on weekends/off-hours |
| Vibrate on alerts | On | Phone vibration for sell alerts |

## Security & Permissions

**Permissions used:**
- `INTERNET` — Yahoo Finance, Zerodha API, GitHub Gist
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` — Background scanning
- `WAKE_LOCK` — Keeps CPU awake during scans
- `ACCESS_WIFI_STATE` — Enables WiFi lock to prevent radio sleep
- `SCHEDULE_EXACT_ALARM` — Automatic discovery scan scheduling
- `POST_NOTIFICATIONS` — Alert push notifications
- `RECEIVE_BOOT_COMPLETED` — Restart after reboot
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Request battery opt-out for reliable scanning

**Credential storage:**
- Zerodha API key and secret: Stored in `SharedPreferences` (unencrypted — clipboard-friendly for manual testing)
- Master password: Not stored. Fetched from GitHub Gist, hashed with SHA-256, compared only
  - Allows you to revoke access to all testers by changing the Gist password
  - Stored hash is only used for auto-login on return visits
- Request token: Obtained through Zerodha's OAuth flow, not persisted
- Access token: Stored temporarily in memory; refreshed every scan

## Architecture

```
com.signalscope.app/
├── data/
│   ├── Models.kt          — Data classes (StockAnalysis, alerts, etc.)
│   ├── ConfigManager.kt   — Credential storage
│   ├── ScoringWeights.kt  — Configurable scoring weights
│   └── DiscoveryResultStore.kt — Persistent scan result cache
├── network/
│   ├── ZerodhaClient.kt   — Zerodha Kite API client (auth + holdings)
│   ├── YahooFinanceClient.kt — Yahoo Finance v8 chart API (candle data)
│   └── StockAiClient.kt   — LLM-powered stock analysis (OpenAI/Anthropic)
├── service/
│   ├── ScanService.kt           — Foreground service (both portfolio + discovery modes)
│   ├── BootReceiver.kt          — Restart after reboot + re-arm discovery scheduler
│   ├── AutoDiscoveryReceiver.kt — AlarmManager receiver for 2-hour scheduled scans
│   └── DiscoveryAutoScheduler   — Manages the ROBUST discovery alarm chain
├── ui/
│   ├── MainActivity.kt    — Dashboard (WebView-based, status, alerts)
│   ├── SettingsActivity.kt— Credential entry + config
│   ├── AlertAdapter.kt    — Alert list renderer
│   ├── HoldingAdapter.kt  — Holding list renderer
│   └── DiscoveryAdapter.kt— Discovery result renderer
└── util/
    ├── Indicators.kt      — All technical indicators (SMA, RSI, MACD, etc.)
    ├── StockAnalyzer.kt   — Buy/sell scoring engine
    └── ValueAnalyzer.kt   — Fundamental value scoring
```

## How Portfolio Scanning Works

1. Service wakes up every N minutes (configurable, default 15 min)
2. Checks if within market hours (9:15–15:30 IST weekdays, configurable)
3. Authenticates with Zerodha via API key + access token
4. Fetches current portfolio holdings
5. For each holding:
   - Fetches candle data from Yahoo Finance (uses cache for 2 years, refreshes today's candle)
   - Runs full technical analysis (SMA, MACD, RSI, Bollinger Bands, ADX, OBV, EMA)
   - Compares sell scores against thresholds
6. Generates alerts (STRONG EXIT, BOOK PROFIT, PROTECT CAPITAL, PEAK WARNING)
7. Sends push notification for each alert (with time-based cooldown: 15–30 min for urgent, once/day for slow-moving)
8. Updates the persistent notification with scan status

## Discovery Scanning

In addition to portfolio monitoring, the app can scan entire markets:

- **NIFTY 500**: All 500 stocks on the National Stock Exchange (via Yahoo Finance)
- **NASDAQ 100**: Top 100 US tech stocks

Discovery scans use the same scoring engine but rank stocks by buy score
to find new opportunities. Results include support/resistance levels,
risk-reward ratios, and AI-powered outlook analysis.

### Two-Tier Discovery System

Discovery scans now come in two flavors, each tuned for its use case:

#### ROBUST Mode (Automatic, Every 2 Hours)
- **When**: Fires automatically at **09:30, 11:30, 13:30, 15:00 IST** on weekdays
  - Survives reboots via `AlarmManager` chain
  - Skips weekends + non-market-hours
- **Speed**: 1500ms pace between stocks, ~30–40 minutes per NIFTY 500
- **Resilience**: Tolerates up to 100 consecutive errors before bailing to retry
  - Retry phase: 3s/stock, 80-error cap
  - Designed to complete even during flaky networks or multitasking
- **Ideal for**: Background discovery while you're not watching

#### FAST Mode (Manual Button Tap)
- **When**: User taps "Scan NIFTY 500" or "Scan NASDAQ 100" on the discovery screen
- **Speed**: 500ms pace between stocks, ~12–15 minutes per NIFTY 500
- **Resilience**: Tolerates up to 50 consecutive errors before retry
  - Retry phase: 2s/stock, 40-error cap
  - Still robust (includes 250ms gap between candle/fundamentals + adaptive pacing)
- **Ideal for**: User-initiated scans where you want results sooner

### Robustness Features (Both Modes)

- **WiFi lock**: Prevents the WiFi radio from sleeping during scans, even when the
  screen is off or the phone is multitasking. This was the root cause of
  "scan stops at 70/120/200 stocks" — fixed by keeping the radio awake.
- **Mutual exclusion**: Portfolio and discovery scans can't run simultaneously
  (they share the same Yahoo Finance session). If you tap "scan portfolio"
  mid-discovery, it queues behind and starts once discovery finishes.
- **Adaptive pacing**: Starts at the set pace, backs off 2× on rate-limit,
  recovers 0.95× on success.
- **Retry queue**: Rate-limited stocks aren't skipped — they're queued for a
  gentler retry phase after the main pass.
- **Periodic save**: Results saved to disk every 25 stocks, so mid-scan process
  death (MIUI Doze) doesn't lose progress.

## Troubleshooting

**Notifications not appearing**
-> Check: Settings -> Apps -> SignalScope -> Notifications -> ensure both
channels ("Scan Status" and "Stock Alerts") are enabled.

**Discovery scan stops partway through (FAST mode)**
-> The most common cause is the screen turning off or the app losing focus while a scan
is running. In FAST mode you're tapping a manual button, so keep the app in focus for
the fastest results. If it still fails:
- Make sure battery optimization is disabled (Settings -> Battery -> SignalScope -> Unrestricted)
- Check vendor-specific battery settings (Xiaomi, Samsung, OnePlus have aggressive doze modes)
- Try the ROBUST mode instead (it tolerates more errors) — just wait for the next 2-hour
  slot or manually trigger `com.signalscope.DISCOVERY_NIFTY500_AUTO` action

**Discovery scan fails frequently in ROBUST mode (automatic)**
-> ROBUST mode is designed to complete even during network flakiness, but persistent
failures suggest:
- Network issues: Check WiFi signal strength, try moving closer to the router
- DNS problems: The app prefers IPv4 on Android 9+. Try restarting WiFi or your router
- Zerodha API connectivity: Open Settings and tap "Test Connection"
- Yahoo Finance outages: This is rare but check [YahooFinance status](https://finance.yahoo.com)

**Scan takes much longer than expected (ROBUST mode)**
-> ROBUST is tuned for reliability over speed — it uses 3× longer pacing (1500ms vs 500ms)
and doubles the error tolerance. This is intentional: it's designed to survive
multitasking, screen-off, and network hiccups. NIFTY 500 typically takes 30–40 minutes
in ROBUST mode. If you want faster results, manually trigger FAST mode instead.

**App crashes during discovery scan**
-> The scan result is saved to disk every 25 stocks, so restart the app and re-open
the discovery screen — previous progress is recovered. If crashes are frequent:
- Check available storage (discovery results are cached)
- Try increasing device RAM usage by closing other apps
- Report the crash with logcat output
