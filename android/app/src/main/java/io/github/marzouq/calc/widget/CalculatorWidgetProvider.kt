package io.github.marzouq.calc.widget

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
import io.github.marzouq.calc.CalcDoc
import io.github.marzouq.calc.CalcEngine
import io.github.marzouq.calc.EvalResult
import io.github.marzouq.calc.Key
import io.github.marzouq.calc.MainActivity
import io.github.marzouq.calc.R

/**
 * Interactive calculator widget.
 *
 * Button taps are self-targeted **broadcasts** (never activities), so the whole
 * keypad keeps working while the device is locked. Only tapping the display
 * opens the full app, which is the one action that asks for an unlock.
 *
 * The provider ships one layout ([R.layout.widget_calculator]) and supplies
 * three [RemoteViews] for the narrow / medium / wide breakpoints — i.e. the
 * 1/3, 2/3 and 3/3 columns of the lock-screen widget grid — differing only in
 * text size.
 */
class CalculatorWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, mgr, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) = render(context, mgr, id)

    override fun onDeleted(context: Context, ids: IntArray) = WidgetStore.remove(context, ids)

    override fun onReceive(context: Context, intent: Intent) {
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

    // -----------------------------------------------------------------

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val doc = WidgetStore.load(context, id)
        val preview = livePreview(doc)
        val remote = RemoteViews(
            mapOf(
                SizeF(56f, 56f) to viewsFor(context, id, doc, preview, Bucket.NARROW),
                SizeF(180f, 100f) to viewsFor(context, id, doc, preview, Bucket.MEDIUM),
                SizeF(260f, 100f) to viewsFor(context, id, doc, preview, Bucket.WIDE),
            ),
        )
        mgr.updateAppWidget(id, remote)
    }

    private fun viewsFor(
        context: Context,
        id: Int,
        doc: CalcDoc,
        preview: String,
        bucket: Bucket,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_calculator)
        v.setTextViewText(R.id.wFormula, doc.expr.ifEmpty { "0" })
        v.setTextViewText(R.id.wResult, preview)
        v.setTextViewTextSize(R.id.wFormula, TypedValue.COMPLEX_UNIT_SP, bucket.formulaSp)
        v.setTextViewTextSize(R.id.wResult, TypedValue.COMPLEX_UNIT_SP, bucket.resultSp)

        for ((viewId, key) in KEY_VIEWS) {
            v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, bucket.keySp)
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
        val intent = Intent(context, CalculatorWidgetProvider::class.java).apply {
            action = ACTION_KEY
            data = Uri.parse("calcwidget://$id/${key.name}")
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

    private enum class Bucket(val formulaSp: Float, val resultSp: Float, val keySp: Float) {
        NARROW(15f, 10f, 12f),
        MEDIUM(20f, 13f, 15f),
        WIDE(26f, 16f, 19f),
    }

    private companion object {
        const val ACTION_KEY = "io.github.marzouq.calc.widget.KEY"
        const val EXTRA_ID = "widget_id"
        const val EXTRA_KEY = "key"
        const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val KEY_VIEWS: Map<Int, Key> = linkedMapOf(
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
