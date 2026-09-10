// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import io.github.kmarzouq.calcplus.CalcDoc
import io.github.kmarzouq.calcplus.CalcEngine
import io.github.kmarzouq.calcplus.EvalResult
import io.github.kmarzouq.calcplus.GraphActivity
import io.github.kmarzouq.calcplus.HistoryActivity
import io.github.kmarzouq.calcplus.Key
import io.github.kmarzouq.calcplus.MainActivity
import io.github.kmarzouq.calcplus.NumFormat
import io.github.kmarzouq.calcplus.ProgDoc
import io.github.kmarzouq.calcplus.ProgFormat
import io.github.kmarzouq.calcplus.ProgOp
import io.github.kmarzouq.calcplus.ProgOutcome
import io.github.kmarzouq.calcplus.ProgrammerActivity
import io.github.kmarzouq.calcplus.R
import io.github.kmarzouq.calcplus.Radix
import io.github.kmarzouq.calcplus.WordSize

/**
 * The full calculator widget — meant to be sized to the largest cell the
 * launcher / lock screen allows, so it can stand in for the whole app on the
 * home screen. It carries both keypads:
 *
 *  - **scientific decimal** (default): trig, logs, roots, powers, `π`, parens
 *    and the number pad, driven by the same decimal engine as the app;
 *  - **programmer** (tap the `1010` chip): 64-bit signed integers, hex entry,
 *    bitwise ops, shifts, rotations and a `BASE` key that cycles the readout
 *    HEX → DEC → BIN — the same programmer engine as [ProgrammerActivity].
 *
 * The top bar links out to the two screens a `RemoteViews` keypad can't host:
 * an **f(x)** chip opens [GraphActivity] and a **history** chip opens
 * [HistoryActivity]. Every keypad tap is a self-targeted broadcast, so the
 * keypads keep working while the device is locked; only the out-links and
 * tapping the display ask for an unlock.
 */
class FullWidgetProvider : AppWidgetProvider() {

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
        val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        when (intent.action) {
            ACTION_MODE -> {
                val next = if (WidgetStore.fullMode(context, id) == "prog") "dec" else "prog"
                WidgetStore.setFullMode(context, id, next)
            }
            ACTION_KEY -> {
                val key = Key.fromName(intent.getStringExtra(EXTRA_KEY)) ?: return
                val cur = WidgetStore.load(context, id)
                val next = when (key) {
                    Key.EQUALS -> when (val r = CalcEngine.evaluate(cur.expr, grouped = true)) {
                        is EvalResult.Ok -> CalcDoc(r.text, evaluated = true)
                        else -> cur
                    }
                    else -> cur.press(key)
                }
                WidgetStore.save(context, id, next)
            }
            ACTION_PKEY -> {
                val token = intent.getStringExtra(EXTRA_KEY) ?: return
                WidgetStore.saveProg(context, id, applyProg(WidgetStore.loadProg(context, id), token))
            }
            else -> return
        }
        render(context, AppWidgetManager.getInstance(context), id)
    }

    // --- programmer editing (mirrors the standalone programmer widget) ---

    private fun applyProg(doc: ProgDoc, token: String): ProgDoc = when (token) {
        "AC" -> doc.cleared()
        "DEL" -> doc.delete()
        "LP" -> doc.open()
        "RP" -> doc.close()
        "NEG" -> doc.negate()
        "NOT" -> doc.not()
        "BASE" -> doc.withRadix(nextRadix(doc.radix), BITS, signed = true)
        "EQ" -> commitProg(doc)
        "ADD" -> doc.op(ProgOp.ADD)
        "SUB" -> doc.op(ProgOp.SUB)
        "MUL" -> doc.op(ProgOp.MUL)
        "DIV" -> doc.op(ProgOp.DIV)
        "MOD" -> doc.op(ProgOp.MOD)
        "AND" -> doc.op(ProgOp.AND)
        "OR" -> doc.op(ProgOp.OR)
        "XOR" -> doc.op(ProgOp.XOR)
        "SHL" -> doc.op(ProgOp.SHL)
        "SHR" -> doc.op(ProgOp.SHR)
        "ROL" -> doc.op(ProgOp.ROL)
        "ROR" -> doc.op(ProgOp.ROR)
        else -> if (token.length == 2 && token[0] == 'D') doc.digit(token[1]) else doc
    }

    private fun commitProg(doc: ProgDoc): ProgDoc {
        val bits = evalProg(doc) ?: return doc
        return ProgDoc.ofValue(bits, doc.radix, BITS, signed = true)
    }

    private fun evalProg(doc: ProgDoc): Long? {
        if (doc.isEmpty || doc.endsOpen()) return null
        return (CalcEngine.programmer(doc.engineInput(), FMT) as? ProgOutcome.Value)
            ?.bits?.and(FMT.mask)
    }

    private fun nextRadix(r: Radix): Radix = when (r) {
        Radix.HEX -> Radix.DEC
        Radix.DEC -> Radix.BIN
        else -> Radix.HEX
    }

    // --- rendering ------------------------------------------------------

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val bucket = bucketFor(mgr, id)
        val v = if (WidgetStore.fullMode(context, id) == "prog") {
            progViews(context, id, bucket)
        } else {
            decViews(context, id, bucket)
        }
        mgr.updateAppWidget(id, v)
    }

    private fun bucketFor(mgr: AppWidgetManager, id: Int): Int {
        val minW = mgr.getAppWidgetOptions(id)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        return when {
            minW >= 250 -> 2
            minW >= 150 -> 1
            else -> 0
        }
    }

    private fun decViews(context: Context, id: Int, bucket: Int): RemoteViews {
        val doc = WidgetStore.load(context, id)
        val v = RemoteViews(context.packageName, R.layout.widget_full)

        val fSp = floatArrayOf(15f, 20f, 26f)[bucket]
        val rSp = floatArrayOf(10f, 13f, 16f)[bucket]
        val keySp = floatArrayOf(12f, 15f, 18f)[bucket]

        v.setTextViewText(R.id.wFormula, doc.expr.ifEmpty { "0" })
        v.setTextViewText(R.id.wResult, decPreview(doc))
        v.setTextViewTextSize(R.id.wFormula, TypedValue.COMPLEX_UNIT_SP, fSp)
        v.setTextViewTextSize(R.id.wResult, TypedValue.COMPLEX_UNIT_SP, rSp)

        for ((viewId, key) in DEC_KEYS) {
            v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, keySp)
            v.setOnClickPendingIntent(viewId, keyBroadcast(context, id, ACTION_KEY, key.name, "dec"))
        }
        v.setOnClickPendingIntent(R.id.wDisplayArea, activity(context, id, SALT_MAIN, MainActivity::class.java))
        wireBar(context, id, v, R.id.wBarFx, R.id.wBarHist, R.id.wBarMode)
        return v
    }

    private fun decPreview(doc: CalcDoc): String = when {
        doc.evaluated || doc.expr.isEmpty() -> ""
        else -> when (val r = CalcEngine.evaluate(doc.expr, grouped = true)) {
            is EvalResult.Ok -> "= ${r.text}"
            is EvalResult.Error -> r.message
            EvalResult.Incomplete -> ""
        }
    }

    private fun progViews(context: Context, id: Int, bucket: Int): RemoteViews {
        val doc = WidgetStore.loadProg(context, id)
        val live = evalProg(doc)
        if (live != null) WidgetStore.saveProgBits(context, id, live)
        val bits = live ?: WidgetStore.loadProgBits(context, id)

        val v = RemoteViews(context.packageName, R.layout.widget_full_prog)
        val fSp = floatArrayOf(13f, 17f, 22f)[bucket]
        val rSp = floatArrayOf(12f, 15f, 19f)[bucket]
        val keySp = floatArrayOf(10f, 13f, 16f)[bucket]

        v.setTextViewText(R.id.wpFormula, doc.display().ifEmpty { "0" })
        v.setTextViewText(
            R.id.wpResult,
            "${doc.radix.label} ${ProgFormat.grouped(bits, doc.radix, BITS, signed = true)}",
        )
        v.setTextViewTextSize(R.id.wpFormula, TypedValue.COMPLEX_UNIT_SP, fSp)
        v.setTextViewTextSize(R.id.wpResult, TypedValue.COMPLEX_UNIT_SP, rSp)

        for ((viewId, token) in PROG_KEYS) {
            v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, keySp)
            v.setOnClickPendingIntent(viewId, keyBroadcast(context, id, ACTION_PKEY, token, "prog"))
        }
        v.setOnClickPendingIntent(
            R.id.wpDisplayArea,
            activity(context, id, SALT_PROG, ProgrammerActivity::class.java),
        )
        wireBar(context, id, v, R.id.wpBarFx, R.id.wpBarHist, R.id.wpBarMode)
        return v
    }

    private fun wireBar(context: Context, id: Int, v: RemoteViews, fx: Int, hist: Int, mode: Int) {
        v.setOnClickPendingIntent(fx, activity(context, id, SALT_GRAPH, GraphActivity::class.java))
        v.setOnClickPendingIntent(hist, activity(context, id, SALT_HISTORY, HistoryActivity::class.java))
        v.setOnClickPendingIntent(mode, modeBroadcast(context, id))
    }

    // --- intents -------------------------------------------------------

    private fun keyBroadcast(
        context: Context,
        id: Int,
        action: String,
        token: String,
        tag: String,
    ): PendingIntent {
        val intent = Intent(context, javaClass).apply {
            this.action = action
            data = Uri.parse("calcwidget://full-$tag/$id/$token")
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KEY, token)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PI_FLAGS)
    }

    private fun modeBroadcast(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, javaClass).apply {
            action = ACTION_MODE
            data = Uri.parse("calcwidget://full-mode/$id")
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PI_FLAGS)
    }

    private fun activity(context: Context, id: Int, salt: Int, cls: Class<*>): PendingIntent {
        val intent = Intent(context, cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, id * 8 + salt, intent, PI_FLAGS)
    }

    private companion object {
        const val ACTION_KEY = "io.github.kmarzouq.calcplus.widget.FULL_KEY"
        const val ACTION_PKEY = "io.github.kmarzouq.calcplus.widget.FULL_PKEY"
        const val ACTION_MODE = "io.github.kmarzouq.calcplus.widget.FULL_MODE"
        const val EXTRA_ID = "widget_id"
        const val EXTRA_KEY = "key"
        const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        const val BITS = 64

        const val SALT_MAIN = 1
        const val SALT_PROG = 2
        const val SALT_GRAPH = 3
        const val SALT_HISTORY = 4

        val FMT = NumFormat.IntFmt(WordSize.QWORD, signed = true)

        /** Scientific-decimal keypad: view id → key. */
        val DEC_KEYS: Map<Int, Key> = linkedMapOf(
            R.id.wKeyClear to Key.CLEAR, R.id.wKeyDelete to Key.DELETE,
            R.id.wKeyPct to Key.PCT, R.id.wKeyDiv to Key.DIV,
            R.id.wKey7 to Key.D7, R.id.wKey8 to Key.D8, R.id.wKey9 to Key.D9, R.id.wKeyMul to Key.MUL,
            R.id.wKey4 to Key.D4, R.id.wKey5 to Key.D5, R.id.wKey6 to Key.D6, R.id.wKeySub to Key.SUB,
            R.id.wKey1 to Key.D1, R.id.wKey2 to Key.D2, R.id.wKey3 to Key.D3, R.id.wKeyAdd to Key.ADD,
            R.id.wKeyNeg to Key.NEG, R.id.wKey0 to Key.D0, R.id.wKeyDot to Key.DOT,
            R.id.wKeyEquals to Key.EQUALS,
            R.id.wKeySin to Key.SIN, R.id.wKeyCos to Key.COS, R.id.wKeyTan to Key.TAN,
            R.id.wKeySqrt to Key.SQRT,
            R.id.wKeyLn to Key.LN, R.id.wKeyLog to Key.LOG, R.id.wKeyPi to Key.PI,
            R.id.wKeyPow to Key.POW,
            R.id.wKeyLparen to Key.LPAREN, R.id.wKeyRparen to Key.RPAREN,
            R.id.wKeySqr to Key.SQR, R.id.wKeyFact to Key.FACT,
        )

        /** Programmer keypad: view id → engine token. */
        val PROG_KEYS: Map<Int, String> = linkedMapOf(
            R.id.wpBase to "BASE", R.id.wpShl to "SHL", R.id.wpShr to "SHR",
            R.id.wpAnd to "AND", R.id.wpOr to "OR", R.id.wpXor to "XOR",
            R.id.wpNot to "NOT", R.id.wpRol to "ROL", R.id.wpRor to "ROR",
            R.id.wpLparen to "LP", R.id.wpRparen to "RP", R.id.wpClear to "AC",
            R.id.wpA to "DA", R.id.wpB to "DB", R.id.wpC to "DC",
            R.id.wpD to "DD", R.id.wpE to "DE", R.id.wpDelete to "DEL",
            R.id.wp7 to "D7", R.id.wp8 to "D8", R.id.wp9 to "D9",
            R.id.wpF to "DF", R.id.wpMod to "MOD", R.id.wpDiv to "DIV",
            R.id.wp4 to "D4", R.id.wp5 to "D5", R.id.wp6 to "D6",
            R.id.wp0 to "D0", R.id.wpNeg to "NEG", R.id.wpMul to "MUL",
            R.id.wp1 to "D1", R.id.wp2 to "D2", R.id.wp3 to "D3",
            R.id.wpSub to "SUB", R.id.wpAdd to "ADD", R.id.wpEquals to "EQ",
        )
    }
}
