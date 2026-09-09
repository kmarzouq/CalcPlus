// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus.widget

import io.github.kmarzouq.calcplus.Key
import io.github.kmarzouq.calcplus.R

/**
 * The scientific calculator widget: the basic keypad plus trig, logs, roots,
 * powers, `π` / `e` and parentheses. Same decimal engine as [CalculatorWidgetProvider].
 */
class SciWidgetProvider : DecimalWidget() {
    override val layoutRes = R.layout.widget_sci
    override val keyTextSp = Triple(11f, 13f, 16f)

    override val keyMap: Map<Int, Key> = NUMERIC_KEYS + linkedMapOf(
        R.id.wKeySin to Key.SIN, R.id.wKeyCos to Key.COS, R.id.wKeyTan to Key.TAN,
        R.id.wKeySqrt to Key.SQRT,
        R.id.wKeyLn to Key.LN, R.id.wKeyLog to Key.LOG, R.id.wKeyPi to Key.PI,
        R.id.wKeyPow to Key.POW,
        R.id.wKeyLparen to Key.LPAREN, R.id.wKeyRparen to Key.RPAREN,
        R.id.wKeySqr to Key.SQR, R.id.wKeyFact to Key.FACT,
    )
}
