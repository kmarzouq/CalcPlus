// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import io.github.kmarzouq.calcplus.databinding.ActivityGraphBinding
import io.github.kmarzouq.calcplus.databinding.GraphFunctionRowBinding

/**
 * `y = f(x)` grapher. Type one or more functions of `x`; the curve updates as
 * you type. Drag to pan, pinch to zoom, tap the graph to trace. The CALC menu
 * has the TI-84 tools: value, zero, minimum, maximum, intersect, dy/dx and
 * ∫f(x)dx.
 */
class GraphActivity : BaseActivity() {

    private lateinit var ui: ActivityGraphBinding
    private val rows = ArrayList<GraphFunctionRowBinding>()
    private var angle = AngleMode.RAD
    private val debounce = Handler(Looper.getMainLooper())
    private val redraw = Runnable { updateGraph() }

    private enum class CalcTool(val label: String, val taps: Int) {
        VALUE("Value  f(x)", 1),
        ZERO("Zero", 2),
        MINIMUM("Minimum", 2),
        MAXIMUM("Maximum", 2),
        INTERSECT("Intersect", 2),
        DYDX("dy/dx", 1),
        INTEGRAL("∫ f(x) dx", 2),
    }

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
        ui.calcButton.setOnClickListener { showCalcMenu() }
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

    // --- CALC menu ----------------------------------------------------

    private fun showCalcMenu() {
        val tools = CalcTool.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.graph_calc)
            .setItems(tools.map { it.label }.toTypedArray()) { _, i -> startTool(tools[i]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> ui.graph.cancelCalc(); showWindow() }
            .show()
    }

    private fun startTool(t: CalcTool) {
        val fns = ui.graph.functions
        if (fns.isEmpty()) {
            toast("Enter a function first"); return
        }
        if (t == CalcTool.INTERSECT && fns.size < 2) {
            toast("Intersect needs two functions"); return
        }
        ui.graph.calcResult = null
        ui.graph.onCalcPicked = { xs -> runTool(t, xs) }
        ui.graph.startCalc(t.taps)
        ui.windowText.text = if (t.taps == 1) {
            "${t.label} — tap the graph at an x"
        } else {
            "${t.label} — tap the left bound, then the right"
        }
    }

    private fun runTool(t: CalcTool, xs: DoubleArray) {
        val fns = ui.graph.functions
        val f0 = fns.firstOrNull()?.expr ?: return
        val a = xs.minOrNull() ?: 0.0
        val b = xs.maxOrNull() ?: 0.0

        fun valueAt(expr: String, x: Double): Double =
            CalcEngine.analyze(expr, 4, x, x, angle).let { if (it.size == 2) it[1] else Double.NaN }

        val res: GraphView.CalcResult = when (t) {
            CalcTool.VALUE -> {
                val x = xs[0]
                GraphView.CalcResult("x = ${fmt(x)}\ny = ${fmt(valueAt(f0, x))}", x to valueAt(f0, x))
            }
            CalcTool.DYDX -> {
                val x = xs[0]
                val r = CalcEngine.analyze(f0, 3, x, x, angle)
                if (r.size == 2) {
                    GraphView.CalcResult("x = ${fmt(x)}\ndy/dx = ${fmt(r[1])}", x to valueAt(f0, x))
                } else {
                    GraphView.CalcResult("dy/dx — undefined here")
                }
            }
            CalcTool.ZERO -> {
                val r = CalcEngine.analyze(f0, 0, a, b, angle)
                if (r.size == 2) {
                    GraphView.CalcResult("zero\nx = ${fmt(r[0])}", r[0] to 0.0)
                } else {
                    GraphView.CalcResult("no sign change in that range")
                }
            }
            CalcTool.MINIMUM, CalcTool.MAXIMUM -> {
                val kind = if (t == CalcTool.MINIMUM) 1 else 2
                val r = CalcEngine.analyze(f0, kind, a, b, angle)
                if (r.size == 2) {
                    GraphView.CalcResult("${t.label.lowercase()}\nx = ${fmt(r[0])}\ny = ${fmt(r[1])}", r[0] to r[1])
                } else {
                    GraphView.CalcResult("${t.label.lowercase()} — not found")
                }
            }
            CalcTool.INTERSECT -> {
                val r = CalcEngine.intersect(fns[0].expr, fns[1].expr, a, b, angle)
                if (r.size == 2) {
                    GraphView.CalcResult("intersection\nx = ${fmt(r[0])}\ny = ${fmt(r[1])}", r[0] to r[1])
                } else {
                    GraphView.CalcResult("no intersection in that range")
                }
            }
            CalcTool.INTEGRAL -> {
                val area = CalcEngine.integrate(f0, a, b, angle)
                if (area.isNaN()) {
                    GraphView.CalcResult("∫ — undefined on that interval")
                } else {
                    GraphView.CalcResult(
                        "∫ from ${fmt(a)} to ${fmt(b)}\n= ${fmt(area)}",
                        shade = a to b,
                        shadeExpr = CalcEngine.normalize(f0),
                    )
                }
            }
        }
        ui.graph.calcResult = res
        showWindow()
    }

    private fun showWindow() {
        val w = ui.graph.windowBounds
        ui.windowText.text = getString(R.string.graph_window, fmt(w[0]), fmt(w[1]), fmt(w[2]), fmt(w[3]))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun fmt(v: Double): String {
        if (v.isNaN()) return "—"
        if (v == 0.0) return "0"
        val a = kotlin.math.abs(v)
        return if (a >= 1e7 || a < 1e-4) String.format("%.4e", v)
        else String.format("%.6f", v).trimEnd('0').trimEnd('.')
    }
}
