// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

/**
 * The editable calculator expression plus a flag for "the last thing that
 * happened was `=`". Pure and immutable: [press] returns a new document, so the
 * same logic drives the activity and the (stateless) widget.
 *
 * [expr] is kept in *display* form — `×`, `÷`, `−` — and normalised to ASCII
 * only when handed to the engine.
 */
data class CalcDoc(
    val expr: String = "",
    val evaluated: Boolean = false,
) {
    val asciiExpression: String get() = CalcEngine.normalize(expr)

    val isEmpty: Boolean get() = expr.isEmpty()

    fun press(key: Key): CalcDoc = when (key) {
        Key.CLEAR -> EMPTY
        Key.DELETE -> delete()
        Key.EQUALS -> this // caller evaluates
        Key.D0, Key.D1, Key.D2, Key.D3, Key.D4,
        Key.D5, Key.D6, Key.D7, Key.D8, Key.D9 -> digit(DIGITS[key.ordinal])
        Key.DOT -> dot()
        Key.ADD -> operator('+')
        Key.SUB -> operator('−')
        Key.MUL -> operator('×')
        Key.DIV -> operator('÷')
        Key.POW -> operator('^')
        Key.PCT -> percent()
        Key.LPAREN -> openParen()
        Key.RPAREN -> closeParen()
        Key.NEG -> toggleSign()

        Key.SIN -> func("sin(")
        Key.COS -> func("cos(")
        Key.TAN -> func("tan(")
        Key.ASIN -> func("asin(")
        Key.ACOS -> func("acos(")
        Key.ATAN -> func("atan(")
        Key.LN -> func("ln(")
        Key.LOG -> func("log(")
        Key.SQRT -> func("√(")
        Key.PI -> value("π")
        Key.EULER -> value("e")
        Key.FACT -> suffix("!")
        Key.RECIP -> suffix("^-1")
    }

    // --- individual rules -------------------------------------------------

    private fun freshIfEvaluated(): String = if (evaluated) "" else expr

    private fun digit(d: Char): CalcDoc {
        val base = if (evaluated) "" else expr
        return CalcDoc(base + d)
    }

    private fun dot(): CalcDoc {
        val base = freshIfEvaluated()
        val seg = trailingNumber(base)
        if (seg.contains('.')) return this
        val prefix = if (seg.isEmpty()) "0" else ""
        return CalcDoc(base + prefix + '.')
    }

    private fun operator(op: Char): CalcDoc {
        // Continue operating on a result.
        val base = expr
        if (base.isEmpty()) {
            return if (op == '−') CalcDoc("−") else this
        }
        val last = base.last()
        if (last in OPERATORS) {
            // Replace a dangling operator, but keep "(" + "-" (unary minus).
            if (last == '(') {
                return if (op == '−') CalcDoc("$base−") else this
            }
            return CalcDoc(base.dropLast(1) + op)
        }
        if (last == '.') return CalcDoc(base.dropLast(1) + op)
        return CalcDoc(base + op, evaluated = false)
    }

    private fun percent(): CalcDoc {
        val base = expr
        val last = base.lastOrNull() ?: return this
        return if (last.isDigit() || last == ')' || last == '%') CalcDoc("$base%") else this
    }

    private fun openParen(): CalcDoc {
        val base = freshIfEvaluated()
        val last = base.lastOrNull()
        val glue = if (last != null && (last.isDigit() || last == ')' || last == '%')) "×(" else "("
        return CalcDoc(base + glue)
    }

    private fun closeParen(): CalcDoc {
        val base = expr
        val opens = base.count { it == '(' }
        val closes = base.count { it == ')' }
        val last = base.lastOrNull() ?: return this
        return if (opens > closes && (last.isDigit() || last == ')' || last == '%')) {
            CalcDoc("$base)")
        } else {
            this
        }
    }

    private fun toggleSign(): CalcDoc {
        val base = freshIfEvaluated()
        val start = numberStart(base)
        return if (start > 0 && base[start - 1] == '−' &&
            (start == 1 || base[start - 2] in OPERATORS)
        ) {
            CalcDoc(base.removeRange(start - 1, start))
        } else {
            CalcDoc(base.substring(0, start) + '−' + base.substring(start))
        }
    }

    private fun delete(): CalcDoc {
        if (expr.isEmpty()) return this
        var s = if (expr.last() == '(') {
            // Drop a whole "sin(" / "√(" token, not one letter at a time.
            expr.dropLast(1).dropLastWhile { it.isLetter() || it == '√' }
        } else {
            expr.dropLast(1)
        }
        // Also drop an implicit "×" left dangling in front of the removed token.
        if (s.isNotEmpty() && s.last() == '×' &&
            expr.getOrNull(s.length)?.let { it.isLetter() || it == '√' || it == '(' } == true
        ) {
            s = s.dropLast(1)
        }
        return CalcDoc(s)
    }

    /** Insert a function call like `sin(`; glue an implicit `×` after a value. */
    private fun func(token: String): CalcDoc {
        val base = freshIfEvaluated()
        return CalcDoc(base + glue(base) + token)
    }

    /** Insert a constant / value token like `π` or `e`. */
    private fun value(token: String): CalcDoc {
        val base = freshIfEvaluated()
        return CalcDoc(base + glue(base) + token)
    }

    /** Append a postfix operator (`!`, `^-1`) — only after a value. */
    private fun suffix(token: String): CalcDoc {
        val last = expr.lastOrNull() ?: return this
        return if (last.isDigit() || last == ')' || last == '%' || last == 'π' || last == 'e') {
            CalcDoc("$expr$token")
        } else {
            this
        }
    }

    private fun glue(base: String): String {
        val last = base.lastOrNull() ?: return ""
        return if (last.isDigit() || last == ')' || last == '%' || last == 'π' || last == 'e') "×" else ""
    }

    private fun trailingNumber(s: String): String = s.substring(numberStart(s))

    /** Index where the trailing run of digits/`.` begins. */
    private fun numberStart(s: String): Int {
        var i = s.length
        while (i > 0 && (s[i - 1].isDigit() || s[i - 1] == '.')) i--
        return i
    }

    companion object {
        val EMPTY = CalcDoc()
        private const val DIGITS = "0123456789"
        private const val OPERATORS = "+−×÷^("
    }
}
