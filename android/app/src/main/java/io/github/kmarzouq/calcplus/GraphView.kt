// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * A `y = f(x)` grapher. Drag to pan, pinch to zoom, tap to trace.
 * Curves are sampled by the Rust engine ([CalcEngine.sample]).
 */
class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Function(val expr: String, val color: Int)

    var functions: List<Function> = emptyList()
        set(value) {
            field = value
            resample()
            invalidate()
        }

    var angle: AngleMode = AngleMode.RAD
        set(value) {
            field = value
            resample()
            invalidate()
        }

    /** Called after a pan/zoom so the host can show the current window. */
    var onWindowChanged: ((xMin: Double, xMax: Double, yMin: Double, yMax: Double) -> Unit)? = null

    private var xMin = -10.0
    private var xMax = 10.0
    private var yMin = -10.0
    private var yMax = 10.0

    private var traceX: Double? = null

    private val samples = HashMap<Function, DoubleArray>()

    private val gridPaint = paint(res(R.color.graph_grid), 1f)
    private val axisPaint = paint(res(R.color.graph_axis), 2f)
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = res(R.color.graph_label)
        textSize = dp(11f)
    }
    private val traceLine = paint(res(R.color.graph_axis), 1.5f).apply { alpha = 140 }
    private val traceDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val traceBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = res(R.color.graph_trace_bg) }
    private val traceText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = res(R.color.display_text)
        textSize = dp(12f)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom(1.0 / d.scaleFactor.toDouble(), d.focusX, d.focusY)
                return true
            }
        },
    )
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    // --- public API -------------------------------------------------------

    fun setWindow(x0: Double, x1: Double, y0: Double, y1: Double) {
        if (x1 > x0 && y1 > y0) {
            xMin = x0; xMax = x1; yMin = y0; yMax = y1
            traceX = null
            resample()
            invalidate()
            notifyWindow()
        }
    }

    /**
     * Reset to the standard −10..10 window (TI-84 "ZStandard"), with the
     * y-range adjusted so the grid stays square for the current view size.
     */
    fun resetWindow() {
        xMin = -10.0; xMax = 10.0
        val half = if (width > 0 && height > 0) {
            10.0 * (height.toDouble() / width)
        } else {
            10.0
        }
        yMin = -half; yMax = half
        traceX = null
        resample()
        invalidate()
        notifyWindow()
    }

    // --- sampling --------------------------------------------------------

    private fun resample() {
        if (width == 0) return
        val n = (width / 2).coerceIn(64, 1024)
        for (f in functions) samples[f] = CalcEngine.sample(f.expr, xMin, xMax, n, angle)
        samples.keys.retainAll(functions.toHashSet())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // on first layout, match the y-scale to the x-scale so the grid is square
        if (ow == 0 && w > 0 && h > 0) {
            val cy = (yMin + yMax) / 2
            val half = (xMax - xMin) / 2 * (h.toDouble() / w)
            yMin = cy - half
            yMax = cy + half
        }
        resample()
    }

    // --- drawing --------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        drawGrid(canvas)
        for (f in functions) drawCurve(canvas, f)
        traceX?.let { drawTrace(canvas, it) }
    }

    private fun pxX(x: Double) = ((x - xMin) / (xMax - xMin) * width).toFloat()
    private fun pxY(y: Double) = (height - (y - yMin) / (yMax - yMin) * height).toFloat()
    private fun mathX(px: Float) = xMin + px / width * (xMax - xMin)

    private fun drawGrid(c: Canvas) {
        val sx = niceStep(xMax - xMin)
        val sy = niceStep(yMax - yMin)
        var gx = floor(xMin / sx) * sx
        while (gx <= xMax) {
            val px = pxX(gx)
            c.drawLine(px, 0f, px, height.toFloat(), gridPaint)
            if (abs(gx) > sx / 2) {
                c.drawText(formatTick(gx, sx), px + dp(2f), pxY(0.0).coerceIn(dp(12f), height - dp(4f)), labelPaint)
            }
            gx += sx
        }
        var gy = floor(yMin / sy) * sy
        while (gy <= yMax) {
            val py = pxY(gy)
            c.drawLine(0f, py, width.toFloat(), py, gridPaint)
            if (abs(gy) > sy / 2) {
                c.drawText(formatTick(gy, sy), pxX(0.0).coerceIn(dp(2f), width - dp(40f)), py - dp(2f), labelPaint)
            }
            gy += sy
        }
        // axes on top
        if (0.0 in xMin..xMax) c.drawLine(pxX(0.0), 0f, pxX(0.0), height.toFloat(), axisPaint)
        if (0.0 in yMin..yMax) c.drawLine(0f, pxY(0.0), width.toFloat(), pxY(0.0), axisPaint)
    }

    private fun drawCurve(c: Canvas, f: Function) {
        val ys = samples[f] ?: return
        if (ys.isEmpty()) return
        curvePaint.color = f.color
        val path = Path()
        var pen = false
        val span = xMax - xMin
        val jumpLimit = (yMax - yMin) * 3
        var prevY = Double.NaN
        for (i in ys.indices) {
            val x = xMin + span * i / (ys.size - 1)
            val y = ys[i]
            if (y.isNaN() || abs(y) > 1e12 ||
                (!prevY.isNaN() && abs(y - prevY) > jumpLimit)
            ) {
                pen = false
            } else {
                val px = pxX(x)
                val py = pxY(y)
                if (pen) path.lineTo(px, py) else path.moveTo(px, py)
                pen = true
            }
            prevY = y
        }
        c.drawPath(path, curvePaint)
    }

    private fun drawTrace(c: Canvas, tx: Double) {
        val px = pxX(tx)
        c.drawLine(px, 0f, px, height.toFloat(), traceLine)
        val lines = ArrayList<String>()
        lines.add("x = ${fmt(tx)}")
        for (f in functions) {
            val y = CalcEngine.sample(f.expr, tx, tx, 1, angle).firstOrNull() ?: continue
            if (y.isFinite()) {
                traceDot.color = f.color
                c.drawCircle(px, pxY(y), dp(4f), traceDot)
                lines.add("y = ${fmt(y)}")
            }
        }
        // label box, kept on screen
        val pad = dp(8f)
        val w = lines.maxOf { traceText.measureText(it) } + pad * 2
        val lh = traceText.fontSpacing
        val h = lh * lines.size + pad
        var lx = px + dp(10f)
        if (lx + w > width) lx = px - dp(10f) - w
        val ly = dp(12f)
        c.drawRoundRect(lx, ly, lx + w, ly + h, dp(8f), dp(8f), traceBg)
        for ((i, s) in lines.withIndex()) {
            c.drawText(s, lx + pad, ly + pad + lh * (i + 0.8f), traceText)
        }
    }

    // --- touch ---------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = e.x; lastTouchY = e.y
                downX = e.x; downY = e.y; dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                val dx = e.x - lastTouchX
                val dy = e.y - lastTouchY
                if (hypot(e.x - downX, e.y - downY) > dp(6f)) dragged = true
                pan(dx, dy)
                lastTouchX = e.x; lastTouchY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    traceX = mathX(e.x)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun pan(dxPx: Float, dyPx: Float) {
        val dx = -dxPx / width * (xMax - xMin)
        val dy = dyPx / height * (yMax - yMin)
        xMin += dx; xMax += dx; yMin += dy; yMax += dy
        resample()
        invalidate()
        notifyWindow()
    }

    private fun zoom(factor: Double, focusX: Float, focusY: Float) {
        val f = factor.coerceIn(0.5, 2.0)
        val fx = mathX(focusX)
        val fy = yMin + (height - focusY) / height * (yMax - yMin)
        xMin = fx + (xMin - fx) * f
        xMax = fx + (xMax - fx) * f
        yMin = fy + (yMin - fy) * f
        yMax = fy + (yMax - fy) * f
        resample()
        invalidate()
        notifyWindow()
    }

    private fun notifyWindow() = onWindowChanged?.invoke(xMin, xMax, yMin, yMax)

    // --- helpers ------------------------------------------------------

    private fun res(id: Int) = resources.getColor(id, context.theme)
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun paint(c: Int, wDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = dp(wDp)
    }

    private fun niceStep(range: Double): Double {
        val raw = range / 8.0
        if (raw <= 0 || raw.isNaN()) return 1.0
        val mag = 10.0.pow(floor(ln(raw) / ln(10.0)))
        val norm = raw / mag
        return mag * when {
            norm < 1.5 -> 1.0
            norm < 3.0 -> 2.0
            norm < 7.0 -> 5.0
            else -> 10.0
        }
    }

    private fun formatTick(v: Double, step: Double): String {
        val r = if (abs(v) < step / 2) 0.0 else v
        return fmt(r)
    }

    private fun fmt(v: Double): String = when {
        v == 0.0 -> "0"
        abs(v) >= 1e6 || abs(v) < 1e-4 -> String.format("%.2e", v)
        else -> {
            val s = String.format("%.4f", v).trimEnd('0').trimEnd('.')
            if (s == "-0") "0" else s
        }
    }

    companion object {
        val PALETTE = intArrayOf(
            Color.parseColor("#1A73E8"),
            Color.parseColor("#D93025"),
            Color.parseColor("#1E8E3E"),
            Color.parseColor("#E37400"),
        )
    }
}
