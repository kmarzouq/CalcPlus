package io.github.marzouq.calc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.app.Activity
import io.github.marzouq.calc.databinding.ActivityMainBinding
import io.github.marzouq.calc.databinding.HistoryRowBinding

class MainActivity : Activity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var history: History
    private var doc = CalcDoc()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        history = History(this)

        savedInstanceState?.getString(STATE_EXPR)?.let {
            doc = CalcDoc(it, savedInstanceState.getBoolean(STATE_EVAL))
        }

        wireKeypad()
        ui.keyClear.setOnLongClickListener {
            history.clear(); refreshHistory(); toast(getString(R.string.history_cleared)); true
        }
        ui.formula.setOnLongClickListener { copy(doc.expr); true }
        ui.result.setOnLongClickListener { copy(ui.result.text.toString()); true }

        render()
        refreshHistory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXPR, doc.expr)
        outState.putBoolean(STATE_EVAL, doc.evaluated)
    }

    private fun wireKeypad() {
        val map = mapOf(
            ui.key0 to Key.D0, ui.key1 to Key.D1, ui.key2 to Key.D2, ui.key3 to Key.D3,
            ui.key4 to Key.D4, ui.key5 to Key.D5, ui.key6 to Key.D6, ui.key7 to Key.D7,
            ui.key8 to Key.D8, ui.key9 to Key.D9,
            ui.keyDot to Key.DOT, ui.keyNeg to Key.NEG,
            ui.keyAdd to Key.ADD, ui.keySub to Key.SUB,
            ui.keyMul to Key.MUL, ui.keyDiv to Key.DIV, ui.keyPct to Key.PCT,
            ui.keyDelete to Key.DELETE, ui.keyClear to Key.CLEAR, ui.keyEquals to Key.EQUALS,
        )
        for ((button, key) in map) button.setOnClickListener { press(key) }
        ui.keyDelete.setOnLongClickListener { doc = CalcDoc(); render(); true }
    }

    private fun press(key: Key) {
        if (key == Key.EQUALS) {
            commit()
            return
        }
        doc = doc.press(key)
        render()
    }

    private fun commit() {
        if (doc.isEmpty) return
        when (val r = CalcEngine.evaluate(doc.expr, grouped = true)) {
            is EvalResult.Ok -> {
                history.add(doc.expr, r.text)
                doc = CalcDoc(r.text, evaluated = true)
                refreshHistory()
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
            else -> when (val r = CalcEngine.evaluate(doc.expr, grouped = true)) {
                is EvalResult.Ok -> "= ${r.text}"
                else -> ""
            }
        }
        ui.formulaScroll.post { ui.formulaScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun refreshHistory() {
        val entries = history.all().asReversed() // oldest first -> newest at bottom
        ui.historyContainer.removeAllViews()
        ui.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        ui.historyScroll.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        for (entry in entries) {
            val row = HistoryRowBinding.inflate(layoutInflater, ui.historyContainer, false)
            row.rowExpression.text = entry.expression
            row.rowResult.text = entry.result
            row.root.setOnClickListener {
                doc = CalcDoc(entry.result, evaluated = true)
                render()
            }
            row.root.setOnLongClickListener { copy(entry.result); true }
            ui.historyContainer.addView(row.root)
        }
        ui.historyScroll.post { ui.historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy(text: String) {
        if (text.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("calculator", text))
        toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val STATE_EXPR = "expr"
        const val STATE_EVAL = "evaluated"
    }
}
