// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import io.github.kmarzouq.calcplus.databinding.ActivityHistoryBinding
import io.github.kmarzouq.calcplus.databinding.HistoryRowBinding

/**
 * Full-screen calculation history, opened from the history icon.
 *
 * Tapping an entry's **result** returns it to be inserted into the current
 * formula; tapping the **expression** returns it to replace the formula.
 * Long-pressing either copies the result.
 */
class HistoryActivity : BaseActivity() {

    private lateinit var ui: ActivityHistoryBinding
    private lateinit var history: History

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(ui.root)
        history = History(this)

        ui.back.setOnClickListener { finish() }
        ui.clear.setOnClickListener { confirmClear() }
        populate()
    }

    private fun populate() {
        val entries = history.all() // newest first
        ui.list.removeAllViews()
        ui.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        ui.scroll.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        ui.clear.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        // oldest -> newest so the freshest sits at the bottom, next to the keypad
        for (entry in entries.asReversed()) {
            val row = HistoryRowBinding.inflate(layoutInflater, ui.list, false)
            row.rowExpression.text = entry.expression
            row.rowResult.text = entry.result
            row.rowResult.setOnClickListener { returnValue(RESULT_VALUE, entry.result) }
            row.rowExpression.setOnClickListener { returnValue(RESULT_EXPRESSION, entry.expression) }
            val copy = View.OnLongClickListener { copy(entry.result); true }
            row.rowResult.setOnLongClickListener(copy)
            row.rowExpression.setOnLongClickListener(copy)
            ui.list.addView(row.root)
        }
        ui.scroll.post { ui.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun returnValue(kind: String, value: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_KIND, kind).putExtra(EXTRA_VALUE, value))
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                history.clear()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                populate()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("calcplus", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_VALUE = "value"
        const val RESULT_VALUE = "value"
        const val RESULT_EXPRESSION = "expression"
    }
}
