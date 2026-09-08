// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus.widget

import android.content.Context
import io.github.kmarzouq.calcplus.CalcDoc

/**
 * Per-widget expression state. Backed by a private SharedPreferences file
 * (`widget_state.xml`) — no permission, survives reboot, never leaves the
 * device. Small enough that we just read/write on the calling thread; widget
 * key presses are already asynchronous broadcasts.
 */
internal object WidgetStore {

    private const val PREFS = "widget_state"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
            .apply()
    }

    fun remove(context: Context, widgetIds: IntArray) {
        val e = prefs(context).edit()
        for (id in widgetIds) e.remove("expr_$id").remove("eval_$id")
        e.apply()
    }
}
