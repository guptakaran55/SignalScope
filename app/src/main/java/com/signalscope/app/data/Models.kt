package com.signalscope.app.data

/**
 * Data models for SignalScope Android.
 * Matches the Python app's data structures exactly.
 *
 * v2: Added DiscoveryScanResult for full-universe discovery scans.
 */

// ═══════════════════════════════════════════════════════
// CANDLE DATA
// ═══════════════════════════════════════════════════════

data class CandleData(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

// ═══════════════════════════════════════════════════════
// PORTFOLIO HOLDING
// ═══════════════════════════════════════════════════════

data class PortfolioHolding(
    val symbol: String,
    val token: String,
    val quantity: Int,
    val avgPrice: Double,
    val ltp: Double,
    val pnl: Double,
    val dayChange: Double = 0.0,
    val dayChangePct: Double = 0.0,
    val exchange: String = "NSE",
    val source: String = "zerodha",
    val analysis: StockAnalysis? = null,
    val verdict: String = "HOLD"
) {
    val invested: Double get() = avgPrice * quantity
    val currentVal: Double get() = ltp * quantity
    val totalReturnPct: Double
        get() = if (invested > 0) (currentVal - invested) / invested * 100.0 else 0.0
}

// ═══════════════════════════════════════════════════════
// STOCK ANALYSIS (output of StockAnalyzer)
// ═══════════════════════════════════════════════════════

data class StockAnalysis(
    val symbol: String,
    val name: String,
    val token: String,
    val price: Double,

    val sma200: Double?,
    val sma200Slope: Double,
    val rsi: Double?,
    val bbUpper: Double?,
    val bbMid: Double?,
    val bbLower: Double?,

    val macd: Double,
    val macdSignal: Double,
    val macdHist: Double,
    val macdSlope: Double,
    val macdAccel: Double,
    val macdPctl: Double,
    val macdLowPct: Double,
    val macd1yLow: Double,
    val macdPhase: String,
    val macdCurve: List<Double> = emptyList(),

    val adx: Double,
    val plusDi: Double,
    val minusDi: Double,
    val obv: Double,

    val ema21: Double?,
    val ema21PctDiff: Double,
    val avgVol20: Double,

    val priceVel: Double,
    val priceAccel: Double,
    val priceRoc3: Double,
    val upDays: Int,

    val support: Double,
    val resistance: Double,
    val risk: Double,
    val reward: Double,
    val rrRatio: Double,

    val atr: Double,
    val riskInAtrs: Double,
    val positionSize: Int,
    val capitalNeeded: Double,
    val potentialProfit: Double,
    val rocPct: Double,

    val buyScore: Int,
    val buySignal: String,

    // Dual sell scoring system — replaces single sellScore
    val profitScore: Int,       // Sub-score A: Profit Booking (max ~58)
    val protectScore: Int,      // Sub-score B: Capital Protection (max ~58)
    val sellIntent: String,     // "STRONG EXIT" | "BOOK PROFIT" | "PROTECT CAPITAL" | "HOLD"

    // Legacy sellScore kept for backward compatibility (= max of profitScore, protectScore)
    val sellScore: Int,
    val sellSignal: String,

    val goldenBuy: Boolean,
    val isBuy: Boolean,
    val isModerateBuy: Boolean,
    val isSell: Boolean,
    val isModerateSell: Boolean,

    // ── Value Analysis (fundamental) ──
    val trailingPe: Double? = null,
    val priceToBook: Double? = null,
    val evToEbitda: Double? = null,
    val debtToEquity: Double? = null,
    val roce: Double? = null,             // Return on Capital Employed
    val dividendYield: Double? = null,    // as percentage
    val operatingCashflow: Double? = null,
    val netIncome: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val sharesOutstanding: Long? = null,
    val sectorMedianPe: Double? = null,   // populated per-scan from sector grouping
    val hasBuyback: Boolean = false,

    // ── Tier 2: Wave projection (opt-in, shown via detail-modal button) ──
    // Projected oscillation range 20 days forward based on EMA50 trend + 2σ envelope.
    // NOT a price forecast — a probabilistic channel.
    val projectedCeiling: Double? = null,
    val projectedFloor: Double? = null,
    val projectedMidpoint: Double? = null,

    val valueScore: Int = 0,              // 0–100 fundamental value score
    val valueRating: String = "N/A"       // "DEEP VALUE" / "MODERATE VALUE" / "MILD VALUE" / "NOT ATTRACTIVE" / "N/A"
)

// ═══════════════════════════════════════════════════════
// ALERTS
// ═══════════════════════════════════════════════════════

enum class AlertType {
    // Intent-based sell alerts (derived from dual scoring)
    STRONG_EXIT,        // profitScore ≥ 35 AND protectScore ≥ 35 — sell all
    BOOK_PROFIT,        // profitScore ≥ 35 AND protectScore < 35 — sell 50-70%, trail rest
    PROTECT_CAPITAL,    // protectScore ≥ 35 AND profitScore < 35 — sell all, trend broken
    PEAK_WARNING,       // earlySell (MACD decelerating) — prepare to sell soon

    // Buy alerts
    GOLDEN_BUY,
    STRONG_BUY,

    // Legacy (kept for backward compat, mapped to new types internally)
    @Deprecated("Use STRONG_EXIT") STRONG_SELL,
    @Deprecated("Use BOOK_PROFIT") MODERATE_SELL,
    @Deprecated("Use PROTECT_CAPITAL") SELL_FLIP,
    @Deprecated("Use PROTECT_CAPITAL") TREND_BREAK,
    @Deprecated("Use PROTECT_CAPITAL") CONSECUTIVE_DECLINE
}

data class StockAlert(
    val symbol: String,
    val name: String,
    val alertType: AlertType,
    val message: String,
    val sellScore: Int,
    val buyScore: Int,
    val price: Double,
    val macdPhase: String,
    val source: String = "zerodha",
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════
// PORTFOLIO SCAN RESULT
// ═══════════════════════════════════════════════════════

data class ScanResult(
    val market: String,
    val startTime: Long,
    val totalScanned: Int,
    val holdings: List<PortfolioHolding>,
    val alerts: List<StockAlert>,
    val durationMs: Long,
    val totalInvested: Double = 0.0,
    val totalCurrent: Double = 0.0,
    val totalPnl: Double = 0.0,
    val sellAlertCount: Int = 0
)

// ═══════════════════════════════════════════════════════
// DISCOVERY SCAN RESULT (NIFTY 500 / NASDAQ 100)
// Mirrors the Python dashboard's "All", "Buy", "Setups" tabs
// ═══════════════════════════════════════════════════════

data class DiscoveryScanResult(
    val market: String,            // "NIFTY 50", "NASDAQ 100", etc.
    val currency: String,          // "₹" or "$"
    val timestamp: Long,
    val totalScanned: Int,
    val totalSymbols: Int,         // how many we attempted
    val skipped: Int,
    val errors: Int,
    val lastError: String = "",    // last error message for UI display
    val isComplete: Boolean,       // false while still scanning

    /** All analyzed stocks, sorted by buy score descending */
    val allStocks: List<StockAnalysis>,

    /** Stocks with buyScore ≥ 60 */
    val buySignals: List<StockAnalysis>,

    /** Stocks with buyScore ≥ 75 */
    val strongBuys: List<StockAnalysis>,

    /** Stocks matching Golden Buy criteria */
    val goldenBuys: List<StockAnalysis>,

    /** Setups: SMA pass + BUY FLIP or EARLY BUY phase */
    val setups: List<StockAnalysis>,

    val durationMs: Long
)
