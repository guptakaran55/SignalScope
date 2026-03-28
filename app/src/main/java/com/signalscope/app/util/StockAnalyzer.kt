package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.StockAnalysis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Analyzes a stock using 6 technical indicators and produces buy/sell scores.
 * This is a direct port of the analyze_stock() function from Python app.py.
 */
object StockAnalyzer {

    private const val MIN_SLOPE_MAG = 0.01

    fun analyze(
        candles: List<CandleData>,
        symbol: String,
        name: String,
        token: String,
        minAvgVolume: Int = 100000
    ): StockAnalysis? {
        val n = candles.size
        if (n < 50) return null

        val closes = candles.map { it.close }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val volumes = candles.map { it.volume }
        val price = closes.last()

        // Volume filter
        val avgVol20 = if (n >= 20) volumes.takeLast(20).average() else volumes.average()
        if (minAvgVolume > 0 && avgVol20 < minAvgVolume) return null

        // ── SMA (200 or 50 fallback) ──
        val hasSma200 = n >= 200
        val hasSma50 = n >= 50
        val smaValues: List<Double?>
        val smaLabel: String
        val smaMaxPts: Int
        val sma200Val: Double?
        val sma200Prev: Double?
        val sma200Slope: Double

        when {
            hasSma200 -> {
                smaValues = Indicators.sma(closes, 200)
                sma200Val = smaValues.last()
                sma200Prev = smaValues[smaValues.size - 2]
                sma200Slope = if (sma200Val != null && sma200Prev != null) sma200Val - sma200Prev else 0.0
                smaLabel = "SMA(200)"
                smaMaxPts = 25
            }
            hasSma50 -> {
                smaValues = Indicators.sma(closes, 50)
                sma200Val = smaValues.last()
                sma200Prev = if (smaValues.size >= 2) smaValues[smaValues.size - 2] else sma200Val
                sma200Slope = if (sma200Val != null && sma200Prev != null) sma200Val - sma200Prev else 0.0
                smaLabel = "SMA(50) fallback"
                smaMaxPts = 15
            }
            else -> {
                sma200Val = null; sma200Prev = null; sma200Slope = 0.0
                smaLabel = "N/A"; smaMaxPts = 0; smaValues = emptyList()
            }
        }

        // ── EMA(21) ──
        val ema21Val: Double?
        val ema21PctDiff: Double
        if (n >= 21) {
            val ema21Series = Indicators.ema(closes, 21)
            ema21Val = ema21Series.last()
            ema21PctDiff = if (ema21Val > 0) ((price - ema21Val) / ema21Val * 100).round(2) else 0.0
        } else {
            ema21Val = null; ema21PctDiff = 0.0
        }

        // ── RSI ──
        val rsiValues = Indicators.rsi(closes, 14)
        val rsiVal = rsiValues.lastOrNull { it != null }

        // ── Bollinger Bands ──
        val bb = Indicators.bollingerBands(closes, 20, 2.0)
        val bbMid = bb.middle.last()
        val bbUpper = bb.upper.last()
        val bbLower = bb.lower.last()

        // ── MACD ──
        val macdResult = Indicators.macd(closes)
        val macdLine = macdResult.macdLine
        val cm = macdLine.last()
        val cmPrev = if (macdLine.size >= 2) macdLine[macdLine.size - 2] else cm
        val cmPrev2 = if (macdLine.size >= 3) macdLine[macdLine.size - 3] else cmPrev
        val cmPrev3 = if (macdLine.size >= 4) macdLine[macdLine.size - 4] else cmPrev2
        val macdSlope = cm - cmPrev
        val macdSlopePrev = cmPrev - cmPrev2
        val macdSlopePrev2 = cmPrev2 - cmPrev3
        val macdAccel = macdSlope - macdSlopePrev
        val macdSignalVal = macdResult.signalLine.last()
        val macdHistVal = macdResult.histogram.last()

        // ── MACD Phase Detection ──
        val wasNeg2d = macdSlopePrev <= -MIN_SLOPE_MAG && macdSlopePrev2 <= -MIN_SLOPE_MAG
        val wasPos2d = macdSlopePrev >= MIN_SLOPE_MAG && macdSlopePrev2 >= MIN_SLOPE_MAG
        val wasNegWeak = macdSlopePrev <= 0 && macdSlopePrev2 <= 0 &&
                (abs(macdSlopePrev) + abs(macdSlopePrev2)) > MIN_SLOPE_MAG
        val wasPosWeak = macdSlopePrev >= 0 && macdSlopePrev2 >= 0 &&
                (abs(macdSlopePrev) + abs(macdSlopePrev2)) > MIN_SLOPE_MAG

        val slopeCrossUp = macdSlope > 0 && (wasNeg2d || wasNegWeak)
        val slopeCrossDn = macdSlope < 0 && (wasPos2d || wasPosWeak)
        val earlyBuy = macdSlope < 0 && macdAccel > 0
        val earlySell = macdSlope > 0 && macdAccel < 0

        val macdPhase = when {
            slopeCrossUp -> "BUY FLIP"
            earlyBuy -> "EARLY BUY"
            slopeCrossDn -> "SELL FLIP"
            earlySell -> "EARLY SELL"
            macdSlope > 0 && macdAccel >= 0 -> "BULLISH"
            macdSlope < 0 && macdAccel <= 0 -> "BEARISH"
            else -> "NEUTRAL"
        }

        // ── MACD 1Y range ──
        val macdDropna = macdLine.filter { !it.isNaN() }
        var macd1yLow = 0.0; var macdLowPct = 0.0; var macdPctl = 50.0
        if (macdDropna.size >= 60) {
            val stableVals = macdDropna.drop(60)
            val recentVals = if (stableVals.size > 650) stableVals.takeLast(650) else stableVals
            macd1yLow = recentVals.min()
            val macd1yHigh = recentVals.max()
            val macdRange = if (macd1yHigh != macd1yLow) macd1yHigh - macd1yLow else 1.0
            macdLowPct = if (macd1yLow < 0) {
                (cm / macd1yLow * 100).coerceIn(0.0, 100.0).round(1)
            } else 0.0
            macdPctl = ((cm - macd1yLow) / macdRange * 100).round(1)
        }

        val macdZeroCrossUp = cm > 0 && cm < 0.15 && cmPrev <= 0
        val macdZeroCrossDn = cm < 0 && cm > -0.15 && cmPrev >= 0

        // ── OBV ──
        val obvValues = Indicators.obv(closes, volumes)
        val obvCurrent = obvValues.last()
        val obv5 = if (obvValues.size >= 5) obvValues[obvValues.size - 5] else obvValues[0]
        val obv20 = if (obvValues.size >= 20) obvValues[obvValues.size - 20] else obvValues[0]

        // ── ADX ──
        val adxResult = Indicators.adx(highs, lows, closes, 14)
        val currAdx = adxResult.adx.lastOrNull() ?: 0.0
        val currPlusDi = adxResult.plusDi.lastOrNull() ?: 0.0
        val currMinusDi = adxResult.minusDi.lastOrNull() ?: 0.0
        val bullishTrend = currPlusDi > currMinusDi

        // ── Bollinger conditions ──
        val touchedLowerBand = (n - 5 until n).any { i ->
            i >= 0 && bb.lower[i] != null && closes[i] <= bb.lower[i]!!
        }
        val touchedUpperBand = (n - 5 until n).any { i ->
            i >= 0 && bb.upper[i] != null && closes[i] >= bb.upper[i]!!
        }
        val belowMidBand = bbMid != null && price <= bbMid

        // ── Support / Resistance ──
        val sr = Indicators.findSupportResistance(highs, lows, closes, min(60, n - 10))
        val risk = (price - sr.support).round(2)
        val reward = (sr.resistance - price).round(2)
        val rrRatio = if (risk > 0) (reward / risk).round(2) else 0.0

        // ── ATR ──
        val atrValues = Indicators.atr(highs, lows, closes, 14)
        val atrVal = atrValues.lastOrNull() ?: 0.0
        val riskInAtrs = if (atrVal > 0 && risk > 0) (risk / atrVal).round(2) else 0.0
        val riskPerTrade = 10000.0
        val positionSize = if (risk > 0) (riskPerTrade / risk).toInt() else 0
        val capitalNeeded = if (positionSize > 0) (positionSize * price).round(0) else 0.0
        val potentialProfit = if (positionSize > 0) (positionSize * reward).round(0) else 0.0
        val rocPct = if (capitalNeeded > 0) (potentialProfit / capitalNeeded * 100).round(2) else 0.0

        // ═══════════════════════════════════════════════════
        // BUY SCORE
        // ═══════════════════════════════════════════════════
        var buyScore = 0

        // 1. SMA trend
        val smaPass = sma200Val != null && price > sma200Val
        val smaPts = if (smaPass) smaMaxPts else 0
        buyScore += smaPts

        // 2. MACD Inflection
        val goldenBuy = macdLowPct >= 60 && macdSlope <= 0.2 && sma200Slope > 0.1
        var macdInfPts = when {
            goldenBuy -> 30
            macdZeroCrossUp && macdAccel > 0 -> 30
            slopeCrossUp -> 20
            earlyBuy -> 10
            else -> 0
        }
        val goldenBonus = if (goldenBuy) 10 else 0
        val pctlBonus = if (macdPctl <= 25 && macdInfPts > 0) 3 else 0
        buyScore += macdInfPts + goldenBonus + pctlBonus

        // 3. RSI graduated scoring
        var rsiPts = 0
        if (rsiVal != null) {
            val r = rsiVal
            rsiPts = when {
                r in 25.0..55.0 -> {
                    if (r <= 35.0) (5 + 15 * (r - 25) / 10).toInt()
                    else (20 * (55 - r) / 20).toInt()
                }
                r < 25.0 -> 3
                else -> 0
            }.coerceIn(0, 20)
        }
        buyScore += rsiPts

        // 4. Bollinger Bands
        val bbPass = belowMidBand || touchedLowerBand
        val bbPts = if (bbPass) 10 + (if (touchedLowerBand) 5 else 0) else 0
        buyScore += bbPts

        // 5. ADX with directional check
        val adxStrong = currAdx > 25
        val adxVeryStrong = currAdx > 30
        val adxPts = if (adxStrong && bullishTrend) {
            15 + (if (adxVeryStrong) 5 else 0)
        } else 0
        buyScore += adxPts

        // 6. OBV
        val obvPass = obvCurrent > obv5 && obvCurrent > obv20
        val obvPts = if (obvPass) 5 else 0
        buyScore += obvPts

        // 7. EMA(21) proximity
        var emaPts = 0
        if (ema21Val != null) {
            val d = ema21PctDiff
            emaPts = when {
                d < -3 -> 3
                d in -3.0..0.0 -> 8
                d in 0.0..2.0 -> 10
                d in 2.0..4.0 -> 5
                d in 4.0..7.0 -> 2
                else -> 0
            }
        }
        buyScore += emaPts

        val buySignal = when {
            buyScore >= 75 -> "STRONG BUY"
            buyScore >= 60 -> "MODERATE BUY"
            else -> "NO SIGNAL"
        }

        // ═══════════════════════════════════════════════════
        // SELL SCORE
        // ═══════════════════════════════════════════════════
        var sellScore = 0

        // 1. SMA break
        val smaSellPass = sma200Val != null && price < sma200Val
        val smaSellPts = if (smaSellPass) 25 else 0
        sellScore += smaSellPts

        // 2. MACD sell inflection
        var sellMacdPts = when {
            macdZeroCrossDn && macdAccel < 0 -> 25
            slopeCrossDn -> 18
            earlySell -> 8
            else -> 0
        }
        val sellPctlBonus = if (macdPctl >= 75 && sellMacdPts > 0) 3 else 0
        sellScore += sellMacdPts + sellPctlBonus

        // 3. RSI overbought
        val rsiSellPts = if (rsiVal != null) {
            when {
                rsiVal > 85.0 -> 10
                rsiVal >= 70.0 -> 20
                rsiVal >= 65.0 -> 15
                rsiVal >= 60.0 -> 8
                else -> 0
            }
        } else 0
        sellScore += rsiSellPts

        // 4. Bollinger stretch
        val bbSellUpper = bbUpper != null && price >= bbUpper
        val bbSellPts = when {
            bbSellUpper -> 10
            touchedUpperBand -> 5
            else -> 0
        }
        sellScore += bbSellPts

        // 5. ADX bearish
        val bearishTrend = currMinusDi > currPlusDi
        val adxSellPts = if (currAdx > 25 && bearishTrend) {
            10 + (if (currAdx > 30) 5 else 0)
        } else 0
        sellScore += adxSellPts

        // 6. OBV declining
        val obvSellPass = obvCurrent < obv5 && obvCurrent < obv20
        val obvSellPts = if (obvSellPass) 10 else 0
        sellScore += obvSellPts

        val hasStructuralSell = smaSellPass || adxSellPts > 0
        val sellSignal = when {
            sellScore >= 65 && hasStructuralSell -> "STRONG SELL"
            sellScore >= 45 -> "MODERATE SELL"
            else -> "NO SIGNAL"
        }

        // ── Price dynamics ──
        val p0 = closes.last()
        val p1 = if (n >= 2) closes[n - 2] else p0
        val p2 = if (n >= 3) closes[n - 3] else p1
        val p3 = if (n >= 4) closes[n - 4] else p2
        val roc3 = if (p3 > 0) (p0 - p3) / p3 * 100 else 0.0
        val priceVel = if (p1 > 0) (p0 - p1) / p1 * 100 else 0.0
        val priceVelPrev = if (p2 > 0) (p1 - p2) / p2 * 100 else 0.0
        val priceAccelVal = priceVel - priceVelPrev
        var upDays = 0
        for (idx in 1 until min(6, n)) {
            if (closes[n - idx] > closes[n - idx - 1]) upDays++ else break
        }

        return StockAnalysis(
            symbol = symbol,
            name = name,
            token = token,
            price = price.round(2),
            sma200 = sma200Val?.round(2),
            sma200Slope = sma200Slope.round(3),
            rsi = rsiVal?.round(2),
            bbUpper = bbUpper?.round(2),
            bbMid = bbMid?.round(2),
            bbLower = bbLower?.round(2),
            macd = cm.round(4),
            macdSignal = macdSignalVal.round(4),
            macdHist = macdHistVal.round(4),
            macdSlope = macdSlope.round(4),
            macdAccel = macdAccel.round(4),
            macdPctl = macdPctl,
            macdLowPct = macdLowPct,
            macd1yLow = macd1yLow.round(2),
            macdPhase = macdPhase,
            adx = currAdx.round(2),
            plusDi = currPlusDi.round(1),
            minusDi = currMinusDi.round(1),
            obv = obvCurrent.round(0),
            ema21 = ema21Val?.round(2),
            ema21PctDiff = ema21PctDiff,
            avgVol20 = avgVol20.round(0),
            priceVel = priceVel.round(2),
            priceAccel = priceAccelVal.round(3),
            priceRoc3 = roc3.round(2),
            upDays = upDays,
            support = sr.support,
            resistance = sr.resistance,
            risk = risk,
            reward = reward,
            rrRatio = rrRatio,
            atr = atrVal.round(2),
            riskInAtrs = riskInAtrs,
            positionSize = positionSize,
            capitalNeeded = capitalNeeded,
            potentialProfit = potentialProfit,
            rocPct = rocPct,
            buyScore = buyScore,
            buySignal = buySignal,
            sellScore = sellScore,
            sellSignal = sellSignal,
            goldenBuy = goldenBuy,
            isBuy = buyScore >= 75,
            isModerateBuy = buyScore >= 60,
            isSell = sellScore >= 65 && hasStructuralSell,
            isModerateSell = sellScore >= 45
        )
    }

    // Extension for rounding
    private fun Double.round(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
