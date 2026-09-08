// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug tool: inflates `R.layout.widget_calculator` at the narrow / medium /
 * wide breakpoints so the 1/3, 2/3, 3/3 lock-screen widget sizes can be
 * eyeballed without a launcher. Buttons are inert here.
 */
class WidgetPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun dp(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
        ).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        for ((label, w, h) in listOf(
            Triple("1 / 3  (narrow)", 124, 236),
            Triple("2 / 3  (medium)", 190, 250),
            Triple("3 / 3  (wide)", 280, 262),
        )) {
            column.addView(TextView(this).apply {
                text = label
                setTextColor(Color.GRAY)
                setPadding(0, dp(16), 0, dp(6))
            })
            val holder = LinearLayout(this)
            val v: View = layoutInflater.inflate(R.layout.widget_calculator, holder, false)
            // The real widget fills these via RemoteViews; do it here for the preview.
            v.findViewById<TextView>(R.id.wFormula)?.text = "12×3+4 = 40"
            holder.addView(v, ViewGroup.LayoutParams(dp(w), dp(h)))
            column.addView(holder)
        }

        setContentView(ScrollView(this).apply {
            addView(column)
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        window.decorView.rootView.let { it.setBackgroundColor(Color.parseColor("#DDDDDD")) }
        column.gravity = Gravity.CENTER_HORIZONTAL
    }
}
