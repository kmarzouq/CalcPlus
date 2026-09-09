// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import io.github.kmarzouq.calcplus.databinding.ActivityMainBinding

class MainActivity : BaseActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var history: History
    private var doc = CalcDoc()
    private var angle = AngleMode.RAD
    private var appliedTheme = ThemeMode.SYSTEM
    private var haptics = true
    private var landscape = false
    private var keysArePills = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedTheme = Settings.theme(this)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        history = History(this)

        angle = Settings.angle(this)
        haptics = Settings.haptics(this)
        savedInstanceState?.getString(STATE_EXPR)?.let {
            doc = CalcDoc(it, savedInstanceState.getBoolean(STATE_EVAL))
        }

        wireKeypad()
        wireScientific()

        ui.historyButton.setOnClickListener { openHistory() }
        ui.graphButton.setOnClickListener {
            startActivity(Intent(this, GraphActivity::class.java))
        }
        ui.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        ui.angleToggle.setOnClickListener {
            angle = angle.next()
            Settings.setAngle(this, angle)
            ui.angleToggle.text = angle.label
            render()
        }
        ui.sciToggle.setOnClickListener {
            setSciExpanded(!Settings.sciOpen(this), animate = true)
        }

        ui.numPad.keyClear.setOnLongClickListener { clearAll(); true }
        ui.numPad.keyDelete.setOnLongClickListener { clearAll(); true }
        ui.formula.setOnLongClickListener { showEditMenu(); true }
        ui.result.setOnLongClickListener { copy(ui.result.text.toString()); true }

        ui.angleToggle.text = angle.label
        if (landscape) {
            ui.sciPad.root.visibility = View.VISIBLE
            ui.sciToggle.visibility = View.GONE
        } else {
            setSciExpanded(Settings.sciOpen(this))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.theme(this) != appliedTheme) recreate()
        haptics = Settings.haptics(this)
        angle = Settings.angle(this)
        ui.angleToggle.text = angle.label
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXPR, doc.expr)
        outState.putBoolean(STATE_EVAL, doc.evaluated)
    }

    // --- scientific pad: expand / collapse ----------------------------

    /**
     * Show or hide the scientific keys. Expanding also shrinks the number
     * keys from circles to rounded rectangles, like the stock calculator.
     * No-op in landscape, where the scientific keys are always shown.
     */
    private fun setSciExpanded(expanded: Boolean, animate: Boolean = false) {
        if (landscape) return
        if (animate) {
            TransitionManager.beginDelayedTransition(
                ui.rootLayout, AutoTransition().setDuration(180),
            )
        }
        ui.sciPad.root.visibility = if (expanded) View.VISIBLE else View.GONE
        ui.displaySpacer.visibility = if (expanded) View.GONE else View.VISIBLE
        ui.sciToggle.alpha = if (expanded) 1f else 0.5f
        applyKeyShape(pills = expanded)
        Settings.setSciOpen(this, expanded)
    }

    /** Swap the number-pad key backgrounds between circle and rounded-rect. */
    private fun applyKeyShape(pills: Boolean) {
        if (keysArePills == pills) return
        keysArePills = pills
        val plain = if (pills) R.drawable.key_background else R.drawable.key_circle
        val op = if (pills) R.drawable.key_background_accent else R.drawable.key_circle_accent
        val eq = if (pills) R.drawable.key_background_equals else R.drawable.key_circle_equals
        with(ui.numPad) {
            listOf(
                key0, key1, key2, key3, key4, key5, key6, key7, key8, key9,
                keyDot, keyNeg, keyDelete, keyClear,
            ).forEach { it.setBackgroundResource(plain) }
            listOf(keyAdd, keySub, keyMul, keyDiv, keyPct)
                .forEach { it.setBackgroundResource(op) }
            keyEquals.setBackgroundResource(eq)
        }
    }

    // --- history -------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun openHistory() {
        startActivityForResult(Intent(this, HistoryActivity::class.java), REQ_HISTORY)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_HISTORY || resultCode != RESULT_OK || data == null) return
        val value = data.getStringExtra(HistoryActivity.EXTRA_VALUE) ?: return
        doc = when (data.getStringExtra(HistoryActivity.EXTRA_KIND)) {
            HistoryActivity.RESULT_EXPRESSION -> CalcDoc(value)
            else -> doc.appendLiteral(value)
        }
        render()
    }

    // --- keypad -------------------------------------------------------

    private fun wireKeypad() = with(ui.numPad) {
        wire(
            key0 to Key.D0, key1 to Key.D1, key2 to Key.D2, key3 to Key.D3,
            key4 to Key.D4, key5 to Key.D5, key6 to Key.D6, key7 to Key.D7,
            key8 to Key.D8, key9 to Key.D9,
            keyDot to Key.DOT, keyNeg to Key.NEG,
            keyAdd to Key.ADD, keySub to Key.SUB,
            keyMul to Key.MUL, keyDiv to Key.DIV, keyPct to Key.PCT,
            keyDelete to Key.DELETE, keyClear to Key.CLEAR, keyEquals to Key.EQUALS,
        )
    }

    private fun wireScientific() = with(ui.sciPad) {
        wire(
            keySin to Key.SIN, keyCos to Key.COS, keyTan to Key.TAN,
            keyAsin to Key.ASIN, keyAcos to Key.ACOS, keyAtan to Key.ATAN,
            keyLn to Key.LN, keyLog to Key.LOG, keySqrt to Key.SQRT,
            keySqr to Key.SQR, keyPow to Key.POW,
            keyFact to Key.FACT, keyRecip to Key.RECIP, keyAbs to Key.ABS,
            keyPi to Key.PI, keyEuler to Key.EULER,
            keyEe to Key.EE, keyComma to Key.COMMA,
            keyLparen to Key.LPAREN, keyRparen to Key.RPAREN,
        )
    }

    private fun wire(vararg keys: Pair<View, Key>) {
        for ((button, key) in keys) button.setOnClickListener { v ->
            if (haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            press(key)
        }
    }

    private fun press(key: Key) {
        if (key == Key.EQUALS) {
            commit()
            return
        }
        doc = doc.press(key)
        render()
    }

    private fun clearAll() {
        doc = CalcDoc()
        render()
    }

    private fun commit() {
        if (doc.isEmpty) return
        when (val r = CalcEngine.evaluate(doc.expr, angle)) {
            is EvalResult.Ok -> {
                history.add(doc.expr, r.text)
                doc = CalcDoc(r.text, evaluated = true)
                render()
            }
            is EvalResult.Error -> ui.result.text = r.message
            EvalResult.Incomplete -> Unit
        }
    }

    private fun render() {
        ui.formula.text = doc.expr
        ui.result.text = when {
            doc.evaluated || doc.isEmpty -> ""
            else -> when (val r = CalcEngine.evaluate(doc.expr, angle)) {
                is EvalResult.Ok -> "= ${r.text}"
                else -> ""
            }
        }
        ui.formulaScroll.post { ui.formulaScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // --- clipboard ----------------------------------------------------

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun showEditMenu() {
        PopupMenu(this, ui.formula, Gravity.END).apply {
            menu.add(0, 1, 0, android.R.string.copy)
            menu.add(0, 2, 1, android.R.string.paste)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> copy(doc.expr)
                    2 -> paste()
                }
                true
            }
        }.show()
    }

    private fun paste() {
        val raw = clipboard().primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val cleaned = raw.filter { it.isDigit() || it in "+-−×÷*/^().%eE," }
        if (cleaned.isEmpty()) {
            toast(getString(R.string.nothing_to_paste)); return
        }
        doc = if (doc.isEmpty || doc.evaluated) CalcDoc(cleaned) else CalcDoc(doc.expr + cleaned)
        render()
        toast(getString(R.string.pasted))
    }

    private fun copy(text: String) {
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("calcplus", text))
        toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val STATE_EXPR = "expr"
        const val STATE_EVAL = "evaluated"
        const val REQ_HISTORY = 1
    }
}
