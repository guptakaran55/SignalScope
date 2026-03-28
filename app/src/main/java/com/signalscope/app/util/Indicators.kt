package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Technical indicator calculations — direct port from Python app.py.
 * All algorithms produce identical results to the Python version.
 */
object Indicators {

    // ═══════════════════════════════════════════════════════
    // MOVING AVERAGES
    // ═══════════════════════════════════════════════════════

    fun sma(data: List<Double>, period: Int): List<Double?> {
        return data.mapIndexed { i, _ ->
            if (i < period - 1) null
            else data.subList(i - period + 1, i + 1).average()
        }
    }

    fun ema(data: List<Double>, period: Int): List<Double> {
        if (data.isEmpty()) return emptyList()
        val result = mutableListOf<Double>()
        val multiplier = 2.0 / (period + 1)
        result.add(data[0])
        for (i in 1 until data.size) {
            result.add(data[i] * multiplier + result[i - 1] * (1 - multiplier))
        }
        return result
    }

    // ═══════════════════════════════════════════════════════
    // RSI
    // ═══════════════════════════════════════════════════════

    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        if (closes.size < period + 1) return List(closes.size) { null }
        val result = mutableListOf<Double?>()
        result.add(null) // first element has no diff

        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()

        for (i in 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            gains.add(if (diff > 0) diff else 0.0)
            losses.add(if (diff < 0) -diff else 0.0)
        }

        // EMA-style (Wilder's) smoothing — matches pandas ewm(com=period-1)
        if (gains.size < period) return List(closes.size) { null }

        var avgGain = gains.subList(0, period).average()
        var avgLoss = losses.subList(0, period).average()

        for (i in 0 until period) result.add(null)

        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
            val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
            result.add(100.0 - (100.0 / (1.0 + rs)))
        }

        return result
    }

    // ═══════════════════════════════════════════════════════
    // BOLLINGER BANDS
    // ═══════════════════════════════════════════════════════

    data class BollingerBands(
        val middle: List<Double?>,
        val upper: List<Double?>,
        val lower: List<Double?>
    )

    fun bollingerBands(closes: List<Double>, period: Int = 20, stdDev: Double = 2.0): BollingerBands {
        val mid = sma(closes, period)
        val upper = mutableListOf<Double?>()
        val lower = mutableListOf<Double?>()

        for (i in closes.indices) {
            val m = mid[i]
            if (m == null || i < period - 1) {
                upper.add(null)
                lower.add(null)
            } else {
                val window = closes.subList(i - period + 1, i + 1)
                val mean = window.average()
                val std = kotlin.math.sqrt(window.map { (it - mean) * (it - mean) }.average())
                upper.add(m + stdDev * std)
                lower.add(m - stdDev * std)
            }
        }
        return BollingerBands(mid, upper, lower)
    }

    // ═══════════════════════════════════════════════════════
    // MACD
    // ═══════════════════════════════════════════════════════

    data class MACDResult(
        val macdLine: List<Double>,
        val signalLine: List<Double>,
        val histogram: List<Double>
    )

    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): MACDResult {
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val macdLine = emaFast.zip(emaSlow).map { (f, s) -> f - s }
        val signalLine = ema(macdLine, signal)
        val histogram = macdLine.zip(signalLine).map { (m, s) -> m - s }
        return MACDResult(macdLine, signalLine, histogram)
    }

    // ═══════════════════════════════════════════════════════
    // OBV
    // ═══════════════════════════════════════════════════════

    fun obv(closes: List<Double>, volumes: List<Long>): List<Double> {
        val result = mutableListOf<Double>()
        result.add(0.0)
        for (i in 1 until closes.size) {
            val direction = sign(closes[i] - closes[i - 1])
            result.add(result.last() + direction * volumes[i])
        }
        return result
    }

    // ═══════════════════════════════════════════════════════
    // ADX with Directional Indicators
    // ═══════════════════════════════════════════════════════

    data class ADXResult(
        val adx: List<Double>,
        val plusDi: List<Double>,
        val minusDi: List<Double>
    )

    fun adx(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): ADXResult {
        val n = closes.size
        if (n < period + 1) return ADXResult(List(n) { 0.0 }, List(n) { 0.0 }, List(n) { 0.0 })

        val plusDm = mutableListOf(0.0)
        val minusDm = mutableListOf(0.0)
        val tr = mutableListOf(highs[0] - lows[0])

        for (i in 1 until n) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            plusDm.add(if (upMove > downMove && upMove > 0) upMove else 0.0)
            minusDm.add(if (downMove > upMove && downMove > 0) downMove else 0.0)

            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - closes[i - 1])
            val tr3 = abs(lows[i] - closes[i - 1])
            tr.add(maxOf(tr1, tr2, tr3))
        }

        val atr = ema(tr, period)
        val smoothPlusDm = ema(plusDm, period)
        val smoothMinusDm = ema(minusDm, period)

        val plusDi = smoothPlusDm.zip(atr).map { (dm, a) ->
            if (a == 0.0) 0.0 else 100.0 * dm / a
        }
        val minusDi = smoothMinusDm.zip(atr).map { (dm, a) ->
            if (a == 0.0) 0.0 else 100.0 * dm / a
        }

        val dx = plusDi.zip(minusDi).map { (p, m) ->
            val sum = p + m
            if (sum == 0.0) 0.0 else 100.0 * abs(p - m) / sum
        }

        val adxLine = ema(dx, period)

        return ADXResult(adxLine, plusDi, minusDi)
    }

    // ═══════════════════════════════════════════════════════
    // ATR
    // ═══════════════════════════════════════════════════════

    fun atr(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): List<Double> {
        val n = closes.size
        if (n < 2) return List(n) { 0.0 }

        val trList = mutableListOf(highs[0] - lows[0])
        for (i in 1 until n) {
            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - closes[i - 1])
            val tr3 = abs(lows[i] - closes[i - 1])
            trList.add(maxOf(tr1, tr2, tr3))
        }
        return ema(trList, period)
    }

    // ═══════════════════════════════════════════════════════
    // SUPPORT & RESISTANCE (swing point clustering)
    // ═══════════════════════════════════════════════════════

    data class SupportResistance(val support: Double, val resistance: Double)

    fun findSupportResistance(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        lookback: Int = 60
    ): SupportResistance {
        val n = closes.size
        val lb = min(lookback, n - 10)
        val price = closes.last()

        val rHighs = highs.takeLast(lb)
        val rLows = lows.takeLast(lb)
        val rCloses = closes.takeLast(lb)

        val window = 5
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()

        for (i in window until rHighs.size - window) {
            var isHigh = true
            var isLow = true
            for (j in 1..window) {
                if (rHighs[i] <= rHighs[i - j] || rHighs[i] <= rHighs[i + j]) isHigh = false
                if (rLows[i] >= rLows[i - j] || rLows[i] >= rLows[i + j]) isLow = false
            }
            if (isHigh) swingHighs.add(rHighs[i])
            if (isLow) swingLows.add(rLows[i])
        }

        fun clusterLevels(levels: List<Double>, thresholdPct: Double = 1.5): List<Double> {
            if (levels.isEmpty()) return emptyList()
            val sorted = levels.sorted()
            val clusters = mutableListOf<Double>()
            val current = mutableListOf(sorted[0])
            for (lv in sorted.drop(1)) {
                if (current.isNotEmpty() && (lv - current[0]) / current[0] * 100 <= thresholdPct) {
                    current.add(lv)
                } else {
                    clusters.add(current.average())
                    current.clear()
                    current.add(lv)
                }
            }
            clusters.add(current.average())
            return clusters
        }

        var resLevels = clusterLevels(swingHighs.filter { it > price })
        var supLevels = clusterLevels(swingLows.filter { it < price })

        // Pivot fallback
        val pivot = (rHighs.last() + rLows.last() + rCloses.last()) / 3
        val r1 = 2 * pivot - rLows.last()
        val s1 = 2 * pivot - rHighs.last()

        if (supLevels.isEmpty()) supLevels = listOf(if (s1 < price) s1 else rLows.min())
        if (resLevels.isEmpty()) resLevels = listOf(if (r1 > price) r1 else rHighs.max())

        val support = supLevels.filter { it < price }.maxOrNull() ?: rLows.min()
        val resistance = resLevels.filter { it > price }.minOrNull() ?: rHighs.max()

        return SupportResistance(
            support = Math.round(support * 100.0) / 100.0,
            resistance = Math.round(resistance * 100.0) / 100.0
        )
    }
}
