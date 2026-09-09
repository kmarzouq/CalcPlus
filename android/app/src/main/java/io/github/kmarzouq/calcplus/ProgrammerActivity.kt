// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kmarzouq.calcplus.databinding.ActivityProgrammerBinding

/**
 * Programmer calculator: fixed-width integer maths in bin / oct / dec / hex,
 * with bitwise ops, shifts and rotations. The Rust engine
 * ([CalcEngine.programmer]) does the arithmetic; this screen is input, the
 * multi-base readout, and the bit grid.
 */
class ProgrammerActivity : BaseActivity() {

    private lateinit var ui: ActivityProgrammerBinding
    private var doc = ProgDoc()
    private var word = WordSize.QWORD
    private var value = 0L
    private val bitCells = arrayOfNulls<TextView>(64)
    private lateinit var baseRows: Map<Radix, Pair<View, TextView>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityProgrammerBinding.inflate(layoutInflater)
        setContentView(ui.root)

        word = Settings.progWord(this)
        doc = ProgDoc.deserialize(Settings.progState(this), Settings.progRadix(this))

        baseRows = mapOf(
            Radix.HEX to (ui.rowHex to ui.valHex),
            Radix.DEC to (ui.rowDec to ui.valDec),
            Radix.OCT to (ui.rowOct to ui.valOct),
            Radix.BIN to (ui.rowBin to ui.valBin),
        )
        baseRows.forEach { (r, pair) -> pair.first.setOnClickListener { setRadix(r) } }

        ui.back.setOnClickListener { finish() }
        ui.wordToggle.text = word.label
        ui.wordToggle.setOnClickListener {
            word = word.next()
            Settings.setProgWord(this, word)
            ui.wordToggle.text = word.label
            value = value and word.mask
            buildBitGrid()
            refresh()
        }

        wireKeypad()
        buildBitGrid()
        updateKeypadState()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Settings.setProgState(this, ProgDoc.serialize(doc))
        Settings.setProgRadix(this, doc.radix)
        Settings.setProgWord(this, word)
    }

    // --- keypad --------------------------------------------------------

    private fun wireKeypad() = with(ui) {
        val digits = mapOf(
            key0 to '0', key1 to '1', key2 to '2', key3 to '3', key4 to '4',
            key5 to '5', key6 to '6', key7 to '7', key8 to '8', key9 to '9',
            keyA to 'A', keyB to 'B', keyC to 'C', keyD to 'D', keyE to 'E', keyF to 'F',
        )
        digits.forEach { (btn, c) -> bind(btn) { doc.digit(c) } }

        val ops = mapOf(
            keyAdd to ProgOp.ADD, keySub to ProgOp.SUB, keyMul to ProgOp.MUL, keyDiv to ProgOp.DIV,
            keyMod to ProgOp.MOD, keyPct to ProgOp.MOD, keyAnd to ProgOp.AND, keyOr to ProgOp.OR,
            keyXor to ProgOp.XOR, keyShl to ProgOp.SHL, keyShr to ProgOp.SHR,
            keyRol to ProgOp.ROL, keyRor to ProgOp.ROR,
        )
        ops.forEach { (btn, o) -> bind(btn) { doc.op(o) } }

        bind(keyNot) { doc.not() }
        bind(keyLparen) { doc.open() }
        bind(keyRparen) { doc.close() }
        bind(keyNeg) { doc.negate() }
        bind(keyDelete) { doc.delete() }
        bind(keyClear) { doc.cleared() }
        keyDelete.setOnLongClickListener { doc = doc.cleared(); refresh(); true }
        keyEquals.setOnClickListener { v ->
            haptic(v)
            commit()
        }
    }

    private inline fun bind(btn: View, crossinline make: () -> ProgDoc) {
        btn.setOnClickListener { v ->
            haptic(v)
            doc = make()
            refresh()
        }
    }

    private fun haptic(v: View) {
        if (Settings.haptics(this)) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun updateKeypadState() {
        val r = doc.radix
        val gated = mapOf(
            ui.key2 to '2', ui.key3 to '3', ui.key4 to '4', ui.key5 to '5', ui.key6 to '6',
            ui.key7 to '7', ui.key8 to '8', ui.key9 to '9', ui.keyA to 'A', ui.keyB to 'B',
            ui.keyC to 'C', ui.keyD to 'D', ui.keyE to 'E', ui.keyF to 'F',
        )
        gated.forEach { (btn, c) ->
            val ok = r.accepts(c)
            btn.isEnabled = ok
            btn.alpha = if (ok) 1f else 0.28f
        }
    }

    // --- actions ------------------------------------------------------

    private fun setRadix(r: Radix) {
        if (r == doc.radix) return
        doc = doc.withRadix(r, word)
        Settings.setProgRadix(this, r)
        updateKeypadState()
        refresh()
    }

    private fun commit() {
        val out = evaluateNow()
        if (out is ProgOutcome.Value) {
            value = out.bits and word.mask
            doc = ProgDoc.ofValue(value, doc.radix, word)
        }
        refresh()
    }

    private fun evaluateNow(): ProgOutcome = when {
        doc.isEmpty -> ProgOutcome.Value(0L)
        doc.endsOpen() -> ProgOutcome.Pending
        else -> CalcEngine.programmer(doc.engineInput(), word.code)
    }

    private fun refresh() {
        ui.formula.text = doc.display()
        ui.formulaScroll.post { ui.formulaScroll.fullScroll(View.FOCUS_RIGHT) }

        when (val out = evaluateNow()) {
            is ProgOutcome.Value -> {
                value = out.bits and word.mask
                showValue()
                ui.status.text = ""
            }
            ProgOutcome.Pending -> {
                showValue()
                ui.status.text = ""
            }
            is ProgOutcome.Error -> ui.status.text = out.message
        }
        highlightActiveBase()
    }

    private fun showValue() {
        baseRows.forEach { (r, pair) -> pair.second.text = ProgFormat.grouped(value, r, word) }
        paintBits()
    }

    private fun highlightActiveBase() {
        baseRows.forEach { (r, pair) ->
            val active = r == doc.radix
            (pair.first as ViewGroup).getChildAt(0).let { label ->
                (label as TextView).setTextColor(
                    themeColor(if (active) R.color.key_text_op else R.color.result_text),
                )
            }
            pair.second.setTextColor(
                themeColor(if (active) R.color.display_text else R.color.result_text),
            )
        }
    }

    // --- bit grid ---------------------------------------------------

    private fun buildBitGrid() {
        ui.bitGrid.removeAllViews()
        bitCells.fill(null)
        val bits = word.bits
        val perRow = minOf(bits, 16)
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        for (rowIdx in 0 until bits / perRow) {
            val hi = bits - 1 - rowIdx * perRow
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            row.addView(
                TextView(this).apply {
                    text = hi.toString()
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(themeColor(R.color.result_text))
                    layoutParams = LinearLayout.LayoutParams(px(26), ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )
            for (col in 0 until perRow) {
                val bitIndex = hi - col
                val cell = TextView(this).apply {
                    text = "0"
                    gravity = Gravity.CENTER
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, px(7), 0, px(7))
                    setBackgroundResource(borderlessRipple)
                    setOnClickListener { toggleBit(bitIndex) }
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply {
                        if (col != 0 && bitIndex % 4 == 3) marginStart = px(6)
                    }
                }
                bitCells[bitIndex] = cell
                row.addView(cell)
            }
            ui.bitGrid.addView(row)
        }
        paintBits()
    }

    private fun paintBits() {
        val on = themeColor(R.color.display_text)
        val off = themeColor(R.color.result_text)
        for (i in 0 until word.bits) {
            val set = (value ushr i) and 1L == 1L
            bitCells[i]?.apply {
                text = if (set) "1" else "0"
                setTextColor(if (set) on else off)
            }
        }
    }

    private fun toggleBit(i: Int) {
        value = (value xor (1L shl i)) and word.mask
        doc = ProgDoc.ofValue(value, doc.radix, word)
        refresh()
    }

    // --- helpers ---------------------------------------------------

    private fun themeColor(id: Int) = resources.getColor(id, theme)

    private val borderlessRipple: Int by lazy {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val id = a.getResourceId(0, 0)
        a.recycle()
        id
    }
}
