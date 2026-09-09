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
import io.github.kmarzouq.calcplus.CalcEngine
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
 * Interactive programmer-calculator widget: 64-bit signed integers, hex entry,
 * bitwise ops, shifts, and a BASE button that cycles the readout between HEX,
 * DEC and BIN. Same broadcast-per-key design as [DecimalWidget] so it works
 * while locked.
 */
class ProgWidgetProvider : AppWidgetProvider() {

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
        val token = intent.getStringExtra(EXTRA_KEY)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || token == null) return

        val doc = WidgetStore.loadProg(context, id)
        WidgetStore.saveProg(context, id, apply(doc, token))
        render(context, AppWidgetManager.getInstance(context), id)
    }

    // --- editing ----------------------------------------------------------

    private fun apply(doc: ProgDoc, token: String): ProgDoc = when (token) {
        "AC" -> doc.cleared()
        "DEL" -> doc.delete()
        "LP" -> doc.open()
        "RP" -> doc.close()
        "NEG" -> doc.negate()
        "NOT" -> doc.not()
        "BASE" -> doc.withRadix(nextRadix(doc.radix), BITS, signed = true)
        "EQ" -> commit(doc)
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

    private fun commit(doc: ProgDoc): ProgDoc {
        val bits = evaluate(doc) ?: return doc
        return ProgDoc.ofValue(bits, doc.radix, BITS, signed = true)
    }

    private fun evaluate(doc: ProgDoc): Long? {
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
        val doc = WidgetStore.loadProg(context, id)
        val live = evaluate(doc)
        if (live != null) WidgetStore.saveProgBits(context, id, live)
        val bits = live ?: WidgetStore.loadProgBits(context, id)

        val opts = mgr.getAppWidgetOptions(id)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val bucket = when {
            minW >= 250 -> 2
            minW >= 150 -> 1
            else -> 0
        }
        val keySp = floatArrayOf(10f, 13f, 16f)[bucket]
        val fSp = floatArrayOf(13f, 17f, 22f)[bucket]
        val rSp = floatArrayOf(12f, 15f, 19f)[bucket]

        val v = RemoteViews(context.packageName, R.layout.widget_prog)
        v.setTextViewText(R.id.wpFormula, doc.display().ifEmpty { "0" })
        v.setTextViewText(
            R.id.wpResult,
            "${doc.radix.label} ${ProgFormat.grouped(bits, doc.radix, BITS, signed = true)}",
        )
        v.setTextViewTextSize(R.id.wpFormula, TypedValue.COMPLEX_UNIT_SP, fSp)
        v.setTextViewTextSize(R.id.wpResult, TypedValue.COMPLEX_UNIT_SP, rSp)

        for ((viewId, token) in KEYS) {
            v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, keySp)
            v.setOnClickPendingIntent(viewId, keyIntent(context, id, token))
        }
        v.setOnClickPendingIntent(R.id.wpDisplayArea, openAppIntent(context, id))
        mgr.updateAppWidget(id, v)
    }

    private fun keyIntent(context: Context, id: Int, token: String): PendingIntent {
        val intent = Intent(context, ProgWidgetProvider::class.java).apply {
            action = ACTION_KEY
            data = Uri.parse("progwidget://$id/$token")
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KEY, token)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PI_FLAGS)
    }

    private fun openAppIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, ProgrammerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, id, intent, PI_FLAGS)
    }

    private companion object {
        const val ACTION_KEY = "io.github.kmarzouq.calcplus.widget.PKEY"
        const val EXTRA_ID = "widget_id"
        const val EXTRA_KEY = "token"
        const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        const val BITS = 64
        val FMT = NumFormat.IntFmt(WordSize.QWORD, signed = true)

        val KEYS: Map<Int, String> = linkedMapOf(
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
