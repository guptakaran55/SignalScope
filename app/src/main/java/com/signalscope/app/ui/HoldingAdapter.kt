package com.signalscope.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.signalscope.app.R
import com.signalscope.app.data.PortfolioHolding

class HoldingAdapter : ListAdapter<PortfolioHolding, HoldingAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PortfolioHolding>() {
            override fun areItemsTheSame(a: PortfolioHolding, b: PortfolioHolding) =
                a.symbol == b.symbol
            override fun areContentsTheSame(a: PortfolioHolding, b: PortfolioHolding) = a == b
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val symbol: TextView = view.findViewById(R.id.holdSymbol)
        val phase: TextView = view.findViewById(R.id.holdPhase)
        val info: TextView = view.findViewById(R.id.holdInfo)
        val buyScore: TextView = view.findViewById(R.id.holdBuyScore)
        val sellScore: TextView = view.findViewById(R.id.holdSellScore)
        val verdict: TextView = view.findViewById(R.id.holdVerdict)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_holding, parent, false))
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val holding = getItem(position)
        val a = holding.analysis

        h.symbol.text = holding.symbol
        h.info.text = "${holding.quantity} qty · Avg ₹${String.format("%,.0f", holding.avgPrice)}"

        if (a != null) {
            h.phase.text = a.macdPhase
            h.phase.visibility = View.VISIBLE
            val phaseColor = when (a.macdPhase) {
                "BUY FLIP", "EARLY BUY", "BULLISH" -> 0xFF059669.toInt()
                "SELL FLIP", "EARLY SELL", "BEARISH" -> 0xFFdc2626.toInt()
                else -> 0xFF94a3b8.toInt()
            }
            h.phase.setTextColor(phaseColor)
            h.buyScore.text = a.buyScore.toString()
            h.sellScore.text = a.sellScore.toString()

            h.buyScore.setTextColor(
                if (a.buyScore >= 75) 0xFF059669.toInt()
                else if (a.buyScore >= 60) 0xFF0891b2.toInt()
                else 0xFF64748b.toInt()
            )
            h.sellScore.setTextColor(
                if (a.sellScore >= 65) 0xFFdc2626.toInt()
                else if (a.sellScore >= 45) 0xFFf59e0b.toInt()
                else 0xFF64748b.toInt()
            )
        } else {
            h.phase.visibility = View.GONE
            h.buyScore.text = "—"
            h.sellScore.text = "—"
            h.buyScore.setTextColor(0xFF64748b.toInt())
            h.sellScore.setTextColor(0xFF64748b.toInt())
        }

        h.verdict.text = holding.verdict
        val (verdictColor, verdictBg) = when (holding.verdict) {
            "SELL NOW" -> 0xFFdc2626.toInt() to 0xFF1a0808.toInt()
            "MOD SELL" -> 0xFFf87171.toInt() to 0xFF1a0808.toInt()
            "BOOK PROFIT?" -> 0xFFf97316.toInt() to 0xFF1a0d08.toInt()
            else -> 0xFF94a3b8.toInt() to 0xFF0f172a.toInt()
        }
        h.verdict.setTextColor(verdictColor)
    }
}
