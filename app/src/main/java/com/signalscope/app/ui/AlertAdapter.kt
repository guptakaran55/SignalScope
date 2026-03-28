package com.signalscope.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.signalscope.app.R
import com.signalscope.app.data.AlertType
import com.signalscope.app.data.StockAlert

class AlertAdapter : ListAdapter<StockAlert, AlertAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<StockAlert>() {
            override fun areItemsTheSame(a: StockAlert, b: StockAlert) =
                a.symbol == b.symbol && a.alertType == b.alertType
            override fun areContentsTheSame(a: StockAlert, b: StockAlert) = a == b
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val strip: View = view.findViewById(R.id.alertStrip)
        val symbol: TextView = view.findViewById(R.id.alertSymbol)
        val badge: TextView = view.findViewById(R.id.alertBadge)
        val message: TextView = view.findViewById(R.id.alertMessage)
        val sellScore: TextView = view.findViewById(R.id.alertSellScore)
        val price: TextView = view.findViewById(R.id.alertPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val alert = getItem(position)

        h.symbol.text = alert.symbol
        h.message.text = alert.message
        h.sellScore.text = alert.sellScore.toString()
        h.price.text = "₹${String.format("%.2f", alert.price)}"

        // Color-code by alert type
        val (stripColor, badgeText, badgeTextColor) = when (alert.alertType) {
            AlertType.STRONG_SELL -> Triple(0xFFdc2626.toInt(), "STRONG SELL", 0xFFdc2626.toInt())
            AlertType.MODERATE_SELL -> Triple(0xFFf59e0b.toInt(), "MOD SELL", 0xFFf59e0b.toInt())
            AlertType.SELL_FLIP -> Triple(0xFFef4444.toInt(), "SELL FLIP", 0xFFef4444.toInt())
            AlertType.TREND_BREAK -> Triple(0xFFef4444.toInt(), "TREND BREAK", 0xFFef4444.toInt())
            AlertType.BOOK_PROFIT -> Triple(0xFFf97316.toInt(), "BOOK PROFIT", 0xFFf97316.toInt())
            AlertType.CONSECUTIVE_DECLINE -> Triple(0xFFef4444.toInt(), "DECLINING", 0xFFef4444.toInt())
            AlertType.GOLDEN_BUY -> Triple(0xFFd97706.toInt(), "★ GOLDEN", 0xFFd97706.toInt())
            AlertType.STRONG_BUY -> Triple(0xFF059669.toInt(), "BUY", 0xFF059669.toInt())
        }

        h.strip.setBackgroundColor(stripColor)
        h.badge.text = badgeText
        h.badge.setTextColor(badgeTextColor)
        h.sellScore.setTextColor(
            if (alert.sellScore >= 65) 0xFFdc2626.toInt()
            else if (alert.sellScore >= 45) 0xFFf59e0b.toInt()
            else 0xFF94a3b8.toInt()
        )
    }
}
