// SPDX-License-Identifier: GPL-2.0-only
@file:Suppress("ApplySharedPref")

package io.github.kmarzouq.calcplus.widget

import android.content.Context
import io.github.kmarzouq.calcplus.CalcDoc
import io.github.kmarzouq.calcplus.ProgDoc
import io.github.kmarzouq.calcplus.Radix

/**
 * Per-widget state. Backed by a private SharedPreferences file
 * (`widget_state.xml`) — no permission, survives reboot, never leaves the
 * device.
 *
 * Writes use `commit()`, not `apply()`: a widget's process is low priority and
 * can be killed between two key-press broadcasts, so the next broadcast must be
 * able to read the previous state off disk immediately. The payload is tiny.
 */
internal object WidgetStore {

    private const val PREFS = "widget_state"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- decimal (basic + full) -----------------------------------------

    fun load(context: Context, widgetId: Int): CalcDoc {
        val p = prefs(context)
        return CalcDoc(
            expr = p.getString("expr_$widgetId", "").orEmpty(),
            evaluated = p.getBoolean("eval_$widgetId", false),
        )
    }

    fun save(context: Context, widgetId: Int, doc: CalcDoc) {
        prefs(context).edit()
            .putString("expr_$widgetId", doc.expr)
            .putBoolean("eval_$widgetId", doc.evaluated)
            .commit()
    }

    // --- full widget: which keypad is showing --------------------------

    /** `"dec"` (scientific decimal) or `"prog"` (programmer). */
    fun fullMode(context: Context, widgetId: Int): String =
        prefs(context).getString("fmode_$widgetId", "dec").orEmpty()

    fun setFullMode(context: Context, widgetId: Int, mode: String) {
        prefs(context).edit().putString("fmode_$widgetId", mode).commit()
    }

    // --- programmer state ---------------------------------------------

    /** The `evaluated` flag is stored explicitly — a half-typed single digit
     *  must not be treated as a finished result (the next digit would wipe it). */
    fun loadProg(context: Context, widgetId: Int): ProgDoc {
        val p = prefs(context)
        val radix = Radix.fromName(p.getString("pradix_$widgetId", null))
        return ProgDoc.deserialize(p.getString("pexpr_$widgetId", "").orEmpty(), radix)
            .copy(evaluated = p.getBoolean("peval_$widgetId", false))
    }

    fun saveProg(context: Context, widgetId: Int, doc: ProgDoc) {
        prefs(context).edit()
            .putString("pexpr_$widgetId", ProgDoc.serialize(doc))
            .putString("pradix_$widgetId", doc.radix.name)
            .putBoolean("peval_$widgetId", doc.evaluated)
            .commit()
    }

    /** Last cleanly-evaluated bit pattern — shown while an expression is mid-edit. */
    fun loadProgBits(context: Context, widgetId: Int): Long =
        prefs(context).getLong("pbits_$widgetId", 0L)

    fun saveProgBits(context: Context, widgetId: Int, bits: Long) {
        prefs(context).edit().putLong("pbits_$widgetId", bits).commit()
    }

    fun remove(context: Context, widgetIds: IntArray) {
        val e = prefs(context).edit()
        for (id in widgetIds) {
            e.remove("expr_$id").remove("eval_$id").remove("fmode_$id")
            e.remove("pexpr_$id").remove("pradix_$id").remove("pbits_$id").remove("peval_$id")
        }
        e.commit()
    }
}
