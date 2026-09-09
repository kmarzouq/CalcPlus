// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus.widget

import io.github.kmarzouq.calcplus.Key
import io.github.kmarzouq.calcplus.R

/** The basic calculator widget: `+ − × ÷ %`, parens-free, one keypad. */
class CalculatorWidgetProvider : DecimalWidget() {
    override val layoutRes = R.layout.widget_calculator
    override val keyMap: Map<Int, Key> = NUMERIC_KEYS
}
