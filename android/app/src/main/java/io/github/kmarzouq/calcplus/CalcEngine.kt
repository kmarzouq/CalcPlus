// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import java.text.DecimalFormatSymbols
import java.util.Locale

/** Outcome of evaluating an expression. */
sealed interface EvalResult {
    data class Ok(val text: String) : EvalResult
    /** Recoverable: incomplete input (e.g. trailing operator). Show nothing. */
    data object Incomplete : EvalResult
    /** A real error the user should see ("Can't divide by zero", "Error"). */
    data class Error(val message: String) : EvalResult
}

/** Angle unit for trig functions. [code] is the wire value for the native call. */
enum class AngleMode(val code: Int, val label: String) {
    RAD(0, "RAD"),
    DEG(1, "DEG"),
    GRAD(2, "GRAD");

    fun next(): AngleMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): AngleMode =
            entries.firstOrNull { it.name == name } ?: RAD
    }
}

/**
 * The one place the app talks to the Rust engine.
 *
 * Converts the pretty display operators to ASCII, forwards the device locale's
 * digit-group and decimal separators + the angle mode, and turns native
 * exceptions into an [EvalResult].
 */
object CalcEngine {

    private const val MAX_DECIMALS = 12
    private const val NO_GROUPING = ' '

    /** U+00D7, U+00B7, U+2217 all mean multiply. */
    private const val TIMES = "×·∗"
    /** U+2212 minus sign, U+2013 en dash, U+2014 em dash. */
    private const val MINUSES = "−–—"

    val nativeAvailable: Boolean get() = NativeBridge.available

    fun version(): String =
        if (NativeBridge.available) runCatching { NativeBridge.nativeVersion() }.getOrDefault("?")
        else "unavailable"

    fun evaluate(
        expression: String,
        angle: AngleMode = AngleMode.RAD,
        grouped: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): EvalResult {
        val ascii = normalize(expression)
        if (ascii.isBlank() || endsOpen(ascii)) return EvalResult.Incomplete
        if (!NativeBridge.available) return EvalResult.Error("Engine unavailable")

        val symbols = DecimalFormatSymbols.getInstance(locale)
        val groupSep = if (grouped) symbols.groupingSeparator else NO_GROUPING

        return try {
            val out = NativeBridge.nativeEval(
                ascii, groupSep, symbols.decimalSeparator, MAX_DECIMALS, angle.code,
            )
            if (out == null) EvalResult.Error("Error") else EvalResult.Ok(out)
        } catch (e: ArithmeticException) {
            EvalResult.Error(friendly(e.message))
        } catch (_: RuntimeException) {
            EvalResult.Error("Error")
        }
    }

    /**
     * Sample `y = f(x)` for graphing. Returns [n] y-values across `[xMin, xMax]`
     * (`NaN` = undefined there), or an **empty** array if [expression] is
     * malformed / the engine is unavailable.
     */
    fun sample(
        expression: String,
        xMin: Double,
        xMax: Double,
        n: Int,
        angle: AngleMode = AngleMode.RAD,
    ): DoubleArray {
        if (!NativeBridge.available) return DoubleArray(0)
        val ascii = normalize(expression)
        if (ascii.isBlank()) return DoubleArray(0)
        return try {
            NativeBridge.nativeSample(ascii, xMin, xMax, n, angle.code)
        } catch (_: RuntimeException) {
            DoubleArray(0)
        }
    }

    /**
     * Evaluate a programmer-calculator integer expression ([engineInput] is
     * already ASCII with base prefixes). [width]: 0=8, 1=16, 2=32, 3=64 bits.
     */
    fun programmer(engineInput: String, width: Int): ProgOutcome {
        if (!NativeBridge.available) return ProgOutcome.Error("Engine unavailable")
        if (engineInput.isBlank()) return ProgOutcome.Pending
        return try {
            ProgOutcome.Value(NativeBridge.nativeProgEval(engineInput, width))
        } catch (e: ArithmeticException) {
            val m = e.message.orEmpty()
            ProgOutcome.Error(
                when {
                    m.contains("divi", ignoreCase = true) -> "Can't divide by zero"
                    m.contains("end") || m.contains("empty") -> "Incomplete"
                    m.contains("large") -> "Number too large"
                    m.contains("unknown", ignoreCase = true) -> "Unknown operator"
                    else -> "Error"
                },
            )
        } catch (_: RuntimeException) {
            ProgOutcome.Error("Error")
        }
    }

    /** Map the pretty display form to plain ASCII the engine can lex. */
    fun normalize(display: String): String = buildString(display.length) {
        val src = display
            .replace("⁻¹", "^-1")
            .replace("²", "^2")
            .replace("³", "^3")
        for (c in src) {
            when {
                c == '÷' -> append('/')
                c == '√' -> append("sqrt")
                c == 'π' -> append("pi")
                c in TIMES -> append('*')
                c in MINUSES -> append('-')
                c.isWhitespace() -> Unit
                else -> append(c) // digits, letters, + - ^ ( ) , % ! . E
            }
        }
    }

    /** True when the expression can't be evaluated yet but isn't wrong. */
    private fun endsOpen(ascii: String): Boolean {
        val t = ascii.trimEnd()
        if (t.isEmpty()) return true
        return when (t.last()) {
            '+', '-', '*', '/', '^', '(', '.', ',', 'E' -> true
            else -> t.count { it == '(' } > t.count { it == ')' }
        }
    }

    private fun friendly(msg: String?): String = when {
        msg == null -> "Error"
        msg.contains("divi", ignoreCase = true) -> "Can't divide by zero"
        msg.contains("too large") -> "Number too large"
        msg.contains("negative") || msg.contains("non-positive") ||
            msg.contains("real number") || msg.contains("defined on") -> "Not a real number"
        msg.contains("factorial") || msg.contains("integer") -> "Bad input"
        else -> "Error"
    }
}
