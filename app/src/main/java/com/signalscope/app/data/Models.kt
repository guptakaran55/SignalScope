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
// SESSION
// ═══════════════════════════════════════════════════════

data class AngelSessionData(
    val jwtToken: String,
    val refreshToken: String,
    val feedToken: String?
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
    val source: String = "angel",
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
    val sellScore: Int,
    val sellSignal: String,

    val goldenBuy: Boolean,
    val isBuy: Boolean,
    val isModerateBuy: Boolean,
    val isSell: Boolean,
    val isModerateSell: Boolean
)

// ═══════════════════════════════════════════════════════
// ALERTS
// ═══════════════════════════════════════════════════════

enum class AlertType {
    STRONG_SELL,
    MODERATE_SELL,
    SELL_FLIP,
    TREND_BREAK,
    BOOK_PROFIT,
    CONSECUTIVE_DECLINE,
    GOLDEN_BUY,
    STRONG_BUY
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
    val source: String = "angel",
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
