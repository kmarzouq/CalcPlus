// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import io.github.kmarzouq.calcplus.databinding.ActivityGraphBinding
import io.github.kmarzouq.calcplus.databinding.GraphFunctionRowBinding

/**
 * `y = f(x)` grapher. Type one or more functions of `x`; the curve updates as
 * you type. Drag to pan, pinch to zoom, tap the graph to trace.
 */
class GraphActivity : BaseActivity() {

    private lateinit var ui: ActivityGraphBinding
    private val rows = ArrayList<GraphFunctionRowBinding>()
    private var angle = AngleMode.RAD
    private val debounce = Handler(Looper.getMainLooper())
    private val redraw = Runnable { updateGraph() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(ui.root)

        angle = Settings.angle(this)
        ui.angleToggle.text = angle.label
        ui.graph.angle = angle

        ui.back.setOnClickListener { finish() }
        ui.resetView.setOnClickListener { ui.graph.resetWindow() }
        ui.angleToggle.setOnClickListener {
            angle = angle.next()
            Settings.setAngle(this, angle)
            ui.angleToggle.text = angle.label
            ui.graph.angle = angle
        }
        ui.addFunction.setOnClickListener { addRow("") }
        ui.graph.onWindowChanged = { x0, x1, y0, y1 ->
            ui.windowText.text = getString(
                R.string.graph_window,
                fmt(x0), fmt(x1), fmt(y0), fmt(y1),
            )
        }

        val saved = Settings.graphFunctions(this)
        if (saved.isEmpty()) addRow("") else saved.forEach { addRow(it) }
        updateGraph()
        ui.graph.post { ui.graph.resetWindow() }
    }

    override fun onPause() {
        super.onPause()
        Settings.setGraphFunctions(this, rows.map { it.exprInput.text.toString() })
    }

    private fun addRow(initial: String) {
        if (rows.size >= GraphView.PALETTE.size) return
        val row = GraphFunctionRowBinding.inflate(layoutInflater, ui.functionList, false)
        row.exprInput.setText(initial)
        row.exprInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                debounce.removeCallbacks(redraw)
                debounce.postDelayed(redraw, 250)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        row.removeRow.setOnClickListener { removeRow(row) }
        rows.add(row)
        ui.functionList.addView(row.root)
        recolor()
        ui.addFunction.visibility = if (rows.size >= GraphView.PALETTE.size) View.GONE else View.VISIBLE
        row.exprInput.requestFocus()
    }

    private fun removeRow(row: GraphFunctionRowBinding) {
        if (rows.size <= 1) {
            row.exprInput.setText("")
            return
        }
        rows.remove(row)
        ui.functionList.removeView(row.root)
        recolor()
        ui.addFunction.visibility = View.VISIBLE
        updateGraph()
    }

    /** Re-assign palette colours by row position. */
    private fun recolor() {
        for ((i, row) in rows.withIndex()) {
            row.colorDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(GraphView.PALETTE[i])
        }
    }

    private fun updateGraph() {
        ui.graph.functions = rows.mapIndexedNotNull { i, row ->
            val expr = row.exprInput.text.toString().trim()
            if (expr.isEmpty()) null else GraphView.Function(expr, GraphView.PALETTE[i])
        }
    }

    private fun fmt(v: Double): String {
        if (v == 0.0) return "0"
        val a = kotlin.math.abs(v)
        return if (a >= 1e5 || a < 1e-3) String.format("%.1e", v)
        else String.format("%.2f", v).trimEnd('0').trimEnd('.')
    }
}
