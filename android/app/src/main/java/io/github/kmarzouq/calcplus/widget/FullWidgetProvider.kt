// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus.widget

import io.github.kmarzouq.calcplus.Key
import io.github.kmarzouq.calcplus.R

/**
 * The full calculator widget — the whole scientific keypad (trig, logs, roots,
 * powers, `π` / `e`, parentheses) plus the number pad. Meant to be sized to the
 * largest cell the launcher / lock screen allows; it is effectively the app on
 * the home screen. Same decimal engine and broadcast-per-key design as
 * [CalculatorWidgetProvider], so every key works while locked.
 */
class FullWidgetProvider : DecimalWidget() {
    override val layoutRes = R.layout.widget_full
    override val keyTextSp = Triple(12f, 15f, 18f)

    override val keyMap: Map<Int, Key> = NUMERIC_KEYS + linkedMapOf(
        R.id.wKeySin to Key.SIN, R.id.wKeyCos to Key.COS, R.id.wKeyTan to Key.TAN,
        R.id.wKeySqrt to Key.SQRT,
        R.id.wKeyLn to Key.LN, R.id.wKeyLog to Key.LOG, R.id.wKeyPi to Key.PI,
        R.id.wKeyPow to Key.POW,
        R.id.wKeyLparen to Key.LPAREN, R.id.wKeyRparen to Key.RPAREN,
        R.id.wKeySqr to Key.SQR, R.id.wKeyFact to Key.FACT,
    )
}
