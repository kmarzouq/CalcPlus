// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kmarzouq.calcplus.databinding.ActivityProgrammerBinding

/**
 * Programmer calculator: integer maths (signed / unsigned, 8–64-bit) and float
 * maths (IEEE presets or a custom sign/exponent/mantissa layout), with a live
 * bin / oct / dec / hex readout and a tappable bit grid. The Rust engine
 * ([CalcEngine.programmer]) does the arithmetic.
 */
class ProgrammerActivity : BaseActivity() {

    private lateinit var ui: ActivityProgrammerBinding
    private var doc = ProgDoc()
    private var format: NumFormat = NumFormat.DEFAULT
    private var value = 0L
    private val bitCells = arrayOfNulls<TextView>(64)
    private lateinit var baseRows: Map<Radix, Pair<View, TextView>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityProgrammerBinding.inflate(layoutInflater)
        setContentView(ui.root)

        format = Settings.progFormat(this)
        val radix = if (format.isFloat) Radix.DEC else Settings.progRadix(this)
        doc = ProgDoc.deserialize(Settings.progState(this), radix)

        baseRows = mapOf(
            Radix.HEX to (ui.rowHex to ui.valHex),
            Radix.DEC to (ui.rowDec to ui.valDec),
            Radix.OCT to (ui.rowOct to ui.valOct),
            Radix.BIN to (ui.rowBin to ui.valBin),
        )
        baseRows.forEach { (r, pair) -> pair.first.setOnClickListener { setRadix(r) } }

        ui.back.setOnClickListener { finish() }
        ui.wordToggle.setOnClickListener { showFormatDialog() }

        wireKeypad()
        buildBitGrid()
        updateKeypadState()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Settings.setProgState(this, ProgDoc.serialize(doc))
        Settings.setProgRadix(this, doc.radix)
        Settings.setProgFormat(this, format)
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
            keyMod to ProgOp.MOD, keyAnd to ProgOp.AND, keyOr to ProgOp.OR, keyXor to ProgOp.XOR,
            keyShl to ProgOp.SHL, keyShr to ProgOp.SHR, keyRol to ProgOp.ROL, keyRor to ProgOp.ROR,
        )
        ops.forEach { (btn, o) -> bind(btn) { doc.op(o) } }

        bind(keyDot) { doc.dot() }
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
        val float = format.isFloat
        val radix = doc.radix

        // hex digit keys — only in an integer hex context
        mapOf(
            ui.keyA to 'A', ui.keyB to 'B', ui.keyC to 'C',
            ui.keyD to 'D', ui.keyE to 'E', ui.keyF to 'F',
        ).forEach { (btn, c) -> enable(btn, !float && radix.accepts(c)) }

        // decimal digit keys — gated by base in integer mode, always on for float
        mapOf(
            ui.key2 to '2', ui.key3 to '3', ui.key4 to '4', ui.key5 to '5', ui.key6 to '6',
            ui.key7 to '7', ui.key8 to '8', ui.key9 to '9',
        ).forEach { (btn, c) -> enable(btn, float || radix.accepts(c)) }

        // bitwise / shift / rotate / mod — integer only
        listOf(
            ui.keyAnd, ui.keyOr, ui.keyXor, ui.keyNot, ui.keyMod,
            ui.keyShl, ui.keyShr, ui.keyRol, ui.keyRor,
        ).forEach { enable(it, !float) }

        // decimal point — float mode only
        enable(ui.keyDot, float)
    }

    private fun enable(btn: Button, on: Boolean) {
        btn.isEnabled = on
        btn.alpha = if (on) 1f else 0.28f
    }

    // --- number-format picker ----------------------------------------

    private fun showFormatDialog() {
        val presets = buildList {
            add(NumFormat.IntFmt(WordSize.BYTE, true) to "Signed 8-bit")
            add(NumFormat.IntFmt(WordSize.WORD, true) to "Signed 16-bit")
            add(NumFormat.IntFmt(WordSize.DWORD, true) to "Signed 32-bit")
            add(NumFormat.IntFmt(WordSize.QWORD, true) to "Signed 64-bit")
            add(NumFormat.IntFmt(WordSize.BYTE, false) to "Unsigned 8-bit")
            add(NumFormat.IntFmt(WordSize.WORD, false) to "Unsigned 16-bit")
            add(NumFormat.IntFmt(WordSize.DWORD, false) to "Unsigned 32-bit")
            add(NumFormat.IntFmt(WordSize.QWORD, false) to "Unsigned 64-bit")
            NumFormat.FLOAT_PRESETS.forEach { (f, name) -> add(f to "Float — $name") }
        }
        val labels = presets.map { it.second }.toMutableList().apply { add(getString(R.string.prog_custom_float)) }
        val current = presets.indexOfFirst { it.first == format }

        AlertDialog.Builder(this)
            .setTitle(R.string.prog_format_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which < presets.size) setFormat(presets[which].first) else showCustomFloatDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomFloatDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_custom_float, null)
        val signBox = v.findViewById<CheckBox>(R.id.signBox)
        val expValue = v.findViewById<TextView>(R.id.expValue)
        val mantValue = v.findViewById<TextView>(R.id.mantValue)
        val total = v.findViewById<TextView>(R.id.totalBits)

        val start = format as? NumFormat.FloatFmt ?: NumFormat.FloatFmt(1, 8, 23)
        var sign = start.sign
        var exp = start.exp
        var mant = start.mant

        fun redraw() {
            signBox.isChecked = sign == 1
            expValue.text = exp.toString()
            mantValue.text = mant.toString()
            val bias = (1 shl (exp - 1)) - 1
            total.text = getString(R.string.prog_cf_total, sign + exp + mant, bias)
        }
        fun clampMant() {
            mant = mant.coerceIn(1, minOf(52, 64 - sign - exp))
        }
        signBox.setOnCheckedChangeListener { _, checked -> sign = if (checked) 1 else 0; clampMant(); redraw() }
        v.findViewById<View>(R.id.expMinus).setOnClickListener { exp = (exp - 1).coerceAtLeast(2); clampMant(); redraw() }
        v.findViewById<View>(R.id.expPlus).setOnClickListener { exp = (exp + 1).coerceAtMost(minOf(30, 63 - sign - 1)); clampMant(); redraw() }
        v.findViewById<View>(R.id.mantMinus).setOnClickListener { mant = (mant - 1).coerceAtLeast(1); redraw() }
        v.findViewById<View>(R.id.mantPlus).setOnClickListener { mant = (mant + 1).coerceAtMost(minOf(52, 64 - sign - exp)); redraw() }
        redraw()

        AlertDialog.Builder(this)
            .setTitle(R.string.prog_cf_title)
            .setView(v)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setFormat(NumFormat.FloatFmt(sign, exp, mant))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- actions ------------------------------------------------------

    private val intSigned: Boolean get() = (format as? NumFormat.IntFmt)?.signed ?: true

    private fun setRadix(r: Radix) {
        if (format.isFloat || r == doc.radix) return
        doc = doc.withRadix(r, format.totalBits, intSigned)
        Settings.setProgRadix(this, r)
        updateKeypadState()
        refresh()
    }

    private fun setFormat(f: NumFormat) {
        if (f == format) return
        val crossed = f.isFloat != format.isFloat
        format = f
        if (crossed) {
            doc = ProgDoc(radix = if (f.isFloat) Radix.DEC else doc.radix)
        }
        ui.wordToggle.text = f.chip
        Settings.setProgFormat(this, f)
        buildBitGrid()
        updateKeypadState()
        refresh()
    }

    private fun commit() {
        val out = evaluateNow()
        if (out is ProgOutcome.Value) {
            value = out.bits and format.mask
            doc = if (format.isFloat) {
                ProgDoc.ofLiteral(reenterable(value), Radix.DEC)
            } else {
                ProgDoc.ofValue(value, doc.radix, format.totalBits, intSigned)
            }
        }
        refresh()
    }

    private fun evaluateNow(): ProgOutcome = when {
        doc.isEmpty -> ProgOutcome.Value(0L)
        doc.endsOpen() -> ProgOutcome.Pending
        else -> CalcEngine.programmer(doc.engineInput(), format)
    }

    private fun refresh() {
        ui.wordToggle.text = format.chip
        ui.formula.text = doc.display()
        ui.formulaScroll.post { ui.formulaScroll.fullScroll(View.FOCUS_RIGHT) }

        when (val out = evaluateNow()) {
            is ProgOutcome.Value -> {
                value = out.bits and format.mask
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
        val w = format.totalBits
        val dec = CalcEngine.programmerFormat(value, format)
        ui.valDec.text = if (format.isFloat) dec else ProgFormat.groupDecimal(dec)
        ui.valHex.text = ProgFormat.grouped(value, Radix.HEX, w)
        ui.valOct.text = ProgFormat.grouped(value, Radix.OCT, w)
        ui.valBin.text = ProgFormat.grouped(value, Radix.BIN, w)
        paintBits()
    }

    private fun highlightActiveBase() {
        val float = format.isFloat
        baseRows.forEach { (r, pair) ->
            val active = !float && r == doc.radix
            (pair.first as ViewGroup).getChildAt(0).let { label ->
                (label as TextView).setTextColor(
                    themeColor(if (active) R.color.key_text_op else R.color.result_text),
                )
            }
            pair.second.setTextColor(
                themeColor(if (active || (float && r == Radix.DEC)) R.color.display_text else R.color.result_text),
            )
        }
    }

    /** A float bit pattern rendered as text the engine can re-lex. */
    private fun reenterable(bits: Long): String {
        val s = CalcEngine.programmerFormat(bits, format).replace('−', '-')
        return when {
            s.endsWith('∞') -> if (s.startsWith('-')) "-inf" else "inf"
            s == "NaN" -> "nan"
            else -> s
        }
    }

    // --- bit grid ---------------------------------------------------

    private fun buildBitGrid() {
        ui.bitGrid.removeAllViews()
        bitCells.fill(null)
        val bits = format.totalBits
        val perRow = minOf(bits, 16)
        val rowCount = (bits + perRow - 1) / perRow
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val fmt = format as? NumFormat.FloatFmt

        for (rowIdx in 0 until rowCount) {
            val rowHi = bits - 1 - rowIdx * perRow
            val rowLo = maxOf(0, rowHi - perRow + 1)
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
                    text = rowHi.toString()
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(themeColor(R.color.result_text))
                    layoutParams = LinearLayout.LayoutParams(px(26), ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )
            // pad a short top row so LSBs line up across rows
            repeat(perRow - (rowHi - rowLo + 1)) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            for (bi in rowHi downTo rowLo) {
                val cell = TextView(this).apply {
                    text = "0"
                    gravity = Gravity.CENTER
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, px(7), 0, px(7))
                    setBackgroundResource(borderlessRipple)
                    setOnClickListener { toggleBit(bi) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        val fieldEdge = fmt != null && (bi == fmt.mant || bi == fmt.mant + fmt.exp)
                        if (bi != rowHi && (fieldEdge || bi % 4 == 3)) marginStart = px(if (fieldEdge) 10 else 6)
                    }
                }
                bitCells[bi] = cell
                row.addView(cell)
            }
            ui.bitGrid.addView(row)
        }
        paintBits()
    }

    private fun paintBits() {
        val fmt = format as? NumFormat.FloatFmt
        val off = themeColor(R.color.result_text)
        val mantOn = themeColor(R.color.display_text)
        val expOn = themeColor(R.color.key_text_op)
        val signOn = themeColor(R.color.key_text_clear)
        for (i in 0 until format.totalBits) {
            val set = (value ushr i) and 1L == 1L
            val on = when {
                fmt == null -> mantOn
                i >= fmt.mant + fmt.exp -> signOn
                i >= fmt.mant -> expOn
                else -> mantOn
            }
            bitCells[i]?.apply {
                text = if (set) "1" else "0"
                setTextColor(if (set) on else off)
            }
        }
    }

    private fun toggleBit(i: Int) {
        value = (value xor (1L shl i)) and format.mask
        doc = if (format.isFloat) {
            ProgDoc.ofLiteral(reenterable(value), Radix.DEC)
        } else {
            ProgDoc.ofValue(value, doc.radix, format.totalBits)
        }
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
