// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import io.github.kmarzouq.calcplus.CalcDoc
import io.github.kmarzouq.calcplus.CalcEngine
import io.github.kmarzouq.calcplus.EvalResult
import io.github.kmarzouq.calcplus.Key
import io.github.kmarzouq.calcplus.MainActivity
import io.github.kmarzouq.calcplus.R

/**
 * Shared machinery for the interactive decimal-calculator widgets (basic and
 * scientific). Subclasses only supply a layout and its key → [Key] map.
 *
 * Button taps are self-targeted **broadcasts** (never activities), so the whole
 * keypad keeps working while the device is locked. Only tapping the display
 * opens the full app, which is the one action that asks for an unlock.
 *
 * Each subclass ships one layout and the provider supplies three [RemoteViews]
 * for the narrow / medium / wide breakpoints — the 1/3, 2/3 and 3/3 columns of
 * the lock-screen widget grid — differing only in text size.
 */
abstract class DecimalWidget : AppWidgetProvider() {

    /** The keypad layout for this widget. */
    protected abstract val layoutRes: Int

    /** view id → key the button emits. Iteration order is irrelevant. */
    protected abstract val keyMap: Map<Int, Key>

    /** Per-bucket key text size in sp; override for denser keypads. */
    protected open val keyTextSp: Triple<Float, Float, Float> = Triple(12f, 15f, 19f)

    final override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, mgr, id)
    }

    final override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) = render(context, mgr, id)

    final override fun onDeleted(context: Context, ids: IntArray) = WidgetStore.remove(context, ids)

    final override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_KEY) return

        val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val key = Key.fromName(intent.getStringExtra(EXTRA_KEY))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || key == null) return

        val current = WidgetStore.load(context, id)
        val next = when (key) {
            Key.EQUALS -> when (val r = CalcEngine.evaluate(current.expr, grouped = true)) {
                is EvalResult.Ok -> CalcDoc(r.text, evaluated = true)
                else -> current
            }
            else -> current.press(key)
        }
        WidgetStore.save(context, id, next)
        render(context, AppWidgetManager.getInstance(context), id)
    }

    // --- rendering ----------------------------------------------------------

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val doc = WidgetStore.load(context, id)
        val preview = livePreview(doc)
        val remote = RemoteViews(
            mapOf(
                SizeF(56f, 56f) to viewsFor(context, id, doc, preview, 0),
                SizeF(180f, 100f) to viewsFor(context, id, doc, preview, 1),
                SizeF(260f, 100f) to viewsFor(context, id, doc, preview, 2),
            ),
        )
        mgr.updateAppWidget(id, remote)
    }

    private fun viewsFor(context: Context, id: Int, doc: CalcDoc, preview: String, bucket: Int): RemoteViews {
        val v = RemoteViews(context.packageName, layoutRes)
        val (formulaSp, resultSp) = when (bucket) {
            0 -> 15f to 10f
            1 -> 20f to 13f
            else -> 26f to 16f
        }
        val keySp = keyTextSp.toList()[bucket]

        v.setTextViewText(R.id.wFormula, doc.expr.ifEmpty { "0" })
        v.setTextViewText(R.id.wResult, preview)
        v.setTextViewTextSize(R.id.wFormula, TypedValue.COMPLEX_UNIT_SP, formulaSp)
        v.setTextViewTextSize(R.id.wResult, TypedValue.COMPLEX_UNIT_SP, resultSp)

        for ((viewId, key) in keyMap) {
            v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, keySp)
            v.setOnClickPendingIntent(viewId, keyIntent(context, id, key))
        }
        v.setOnClickPendingIntent(R.id.wDisplayArea, openAppIntent(context, id))
        return v
    }

    private fun livePreview(doc: CalcDoc): String = when {
        doc.evaluated || doc.expr.isEmpty() -> ""
        else -> when (val r = CalcEngine.evaluate(doc.expr, grouped = true)) {
            is EvalResult.Ok -> "= ${r.text}"
            is EvalResult.Error -> r.message
            EvalResult.Incomplete -> ""
        }
    }

    private fun keyIntent(context: Context, id: Int, key: Key): PendingIntent {
        val intent = Intent(context, javaClass).apply {
            action = ACTION_KEY
            data = Uri.parse("calcwidget://${javaClass.simpleName}/$id/${key.name}")
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KEY, key.name)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PI_FLAGS)
    }

    private fun openAppIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, id, intent, PI_FLAGS)
    }

    protected companion object {
        const val ACTION_KEY = "io.github.kmarzouq.calcplus.widget.KEY"
        const val EXTRA_ID = "widget_id"
        const val EXTRA_KEY = "key"
        const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** The numeric keys every decimal widget shares. */
        val NUMERIC_KEYS: Map<Int, Key> = linkedMapOf(
            R.id.wKeyClear to Key.CLEAR, R.id.wKeyDelete to Key.DELETE,
            R.id.wKeyPct to Key.PCT, R.id.wKeyDiv to Key.DIV,
            R.id.wKey7 to Key.D7, R.id.wKey8 to Key.D8, R.id.wKey9 to Key.D9,
            R.id.wKeyMul to Key.MUL,
            R.id.wKey4 to Key.D4, R.id.wKey5 to Key.D5, R.id.wKey6 to Key.D6,
            R.id.wKeySub to Key.SUB,
            R.id.wKey1 to Key.D1, R.id.wKey2 to Key.D2, R.id.wKey3 to Key.D3,
            R.id.wKeyAdd to Key.ADD,
            R.id.wKeyNeg to Key.NEG, R.id.wKey0 to Key.D0, R.id.wKeyDot to Key.DOT,
            R.id.wKeyEquals to Key.EQUALS,
        )
    }
}
