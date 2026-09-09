// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

/**
 * Model for the programmer calculator: a number format ([NumFormat]), an entry
 * base ([Radix]) and an editable token list ([ProgDoc]). Arithmetic is done by
 * the Rust engine ([CalcEngine.programmer]); everything here is input handling
 * and formatting of the raw bit pattern.
 */

/** Input / display base for integer modes. */
enum class Radix(val base: Int, val label: String, val enginePrefix: String) {
    HEX(16, "HEX", "0x"),
    DEC(10, "DEC", ""),
    OCT(8, "OCT", "0o"),
    BIN(2, "BIN", "0b");

    /** True if [c] is a legal digit in this base (case-insensitive for hex). */
    fun accepts(c: Char): Boolean = Character.digit(c, base) >= 0

    companion object {
        fun fromName(name: String?): Radix = entries.firstOrNull { it.name == name } ?: HEX
    }
}

/** Register width for integer modes. */
enum class WordSize(val bits: Int) {
    BYTE(8), WORD(16), DWORD(32), QWORD(64);

    val code: Int get() = ordinal
    val label: String get() = bits.toString()

    fun next(): WordSize = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): WordSize = entries.firstOrNull { it.name == name } ?: QWORD
        fun ofBits(bits: Int): WordSize = entries.firstOrNull { it.bits == bits } ?: QWORD
    }
}

/**
 * How the calculator interprets its numbers: a signed/unsigned integer of a
 * fixed width, or an IEEE-style float with a chosen field layout.
 */
sealed interface NumFormat {
    data class IntFmt(val width: WordSize, val signed: Boolean) : NumFormat
    data class FloatFmt(val sign: Int, val exp: Int, val mant: Int) : NumFormat

    val totalBits: Int
        get() = when (this) {
            is IntFmt -> width.bits
            is FloatFmt -> sign + exp + mant
        }

    /** Low [totalBits] set, as a `Long`. */
    val mask: Long get() = if (totalBits == 64) -1L else (1L shl totalBits) - 1L

    val isFloat: Boolean get() = this is FloatFmt

    // wire form for the native calls: mode, then three params
    val mode: Int
        get() = when (this) {
            is IntFmt -> if (signed) 0 else 1
            is FloatFmt -> 2
        }
    val p1: Int get() = when (this) { is IntFmt -> width.code; is FloatFmt -> sign }
    val p2: Int get() = if (this is FloatFmt) exp else 0
    val p3: Int get() = if (this is FloatFmt) mant else 0

    /** Short label for the app-bar chip. */
    val chip: String
        get() = when (this) {
            is IntFmt -> (if (signed) "i" else "u") + width.bits
            is FloatFmt -> floatName(sign, exp, mant)
        }

    fun serialize(): String = when (this) {
        is IntFmt -> "I:${width.bits}:${if (signed) "S" else "U"}"
        is FloatFmt -> "F:$sign:$exp:$mant"
    }

    companion object {
        val DEFAULT: NumFormat = IntFmt(WordSize.DWORD, signed = true)

        /** (sign, exp, mant) -> name, and the preset list for the picker. */
        val FLOAT_PRESETS: List<Pair<FloatFmt, String>> = listOf(
            FloatFmt(1, 5, 10) to "half (16-bit)",
            FloatFmt(1, 8, 7) to "bfloat16",
            FloatFmt(1, 8, 23) to "float (32-bit)",
            FloatFmt(1, 11, 52) to "double (64-bit)",
        )

        fun floatName(sign: Int, exp: Int, mant: Int): String = when {
            sign == 1 && exp == 5 && mant == 10 -> "f16"
            sign == 1 && exp == 8 && mant == 7 -> "bf16"
            sign == 1 && exp == 8 && mant == 23 -> "f32"
            sign == 1 && exp == 11 && mant == 52 -> "f64"
            else -> "$sign·$exp·$mant"
        }

        fun parse(s: String?): NumFormat {
            if (s == null) return DEFAULT
            val parts = s.split(':')
            return try {
                when (parts[0]) {
                    "I" -> IntFmt(WordSize.ofBits(parts[1].toInt()), parts[2] == "S")
                    "F" -> FloatFmt(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
                    else -> DEFAULT
                }
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}

/** Result of a programmer-calculator evaluation. */
sealed interface ProgOutcome {
    /** Masked bit pattern of the result. */
    data class Value(val bits: Long) : ProgOutcome
    /** Nothing to evaluate yet (empty, or a half-typed expression). */
    data object Pending : ProgOutcome
    data class Error(val message: String) : ProgOutcome
}

/**
 * A programmer-calculator operator. [display] is what the user sees; [engine]
 * is what the Rust lexer accepts. Keyword operators are padded with spaces so
 * they never fuse with a hex literal.
 */
enum class ProgOp(val display: String, val engine: String) {
    ADD("+", "+"),
    SUB("−", "-"),
    MUL("×", "*"),
    DIV("÷", "/"),
    MOD("MOD", " mod "),
    AND("AND", " and "),
    OR("OR", " or "),
    XOR("XOR", " xor "),
    SHL("<<", "<<"),
    SHR(">>", ">>"),
    ROL("RoL", " rol "),
    ROR("RoR", " ror ");
}

/** One token of a programmer expression. */
sealed interface ProgTok {
    data class Lit(val digits: String) : ProgTok
    data class Bin(val op: ProgOp) : ProgTok
    data object Not : ProgTok
    data object Open : ProgTok
    data object Close : ProgTok
}

/** Base/width-aware formatting of a raw bit pattern. */
object ProgFormat {

    /** Bare digits of [bits] in [radix] over [width] bits — no grouping. */
    fun digitsOf(bits: Long, radix: Radix, width: Int, signed: Boolean = true): String {
        val masked = bits and maskOf(width)
        return when {
            radix != Radix.DEC -> java.lang.Long.toUnsignedString(masked, radix.base).uppercase()
            signed -> signExtend(masked, width).toString()
            else -> java.lang.Long.toUnsignedString(masked)
        }
    }

    /** Display string of [bits] in [radix] over [width] bits, grouped. */
    fun grouped(bits: Long, radix: Radix, width: Int, signed: Boolean = true): String {
        val masked = bits and maskOf(width)
        if (radix == Radix.DEC) return groupDecimal(digitsOf(bits, radix, width, signed))
        val raw = java.lang.Long.toUnsignedString(masked, radix.base).uppercase()
        return group(raw, if (radix == Radix.OCT) 3 else 4)
    }

    /** Group a plain decimal integer string (keeping any leading `-`) by 3s. */
    fun groupDecimal(s: String): String {
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        return (if (neg) "-" else "") + group(body, 3)
    }

    /** Interpret [masked] (a [width]-bit pattern) as signed two's-complement. */
    fun signExtend(masked: Long, width: Int): Long {
        if (width >= 64) return masked
        val m = 1L shl (width - 1)
        return (masked xor m) - m
    }

    private fun maskOf(width: Int): Long = if (width >= 64) -1L else (1L shl width) - 1L

    private fun group(s: String, n: Int): String {
        if (s.length <= n) return s
        val sb = StringBuilder(s.length + s.length / n)
        val lead = s.length % n
        for (i in s.indices) {
            if (i > 0 && (i - lead) % n == 0) sb.append(' ')
            sb.append(s[i])
        }
        return sb.toString()
    }
}

/**
 * The editable programmer expression. Immutable: every edit returns a new doc,
 * mirroring [CalcDoc]. Integer literals are stored as digit strings in the
 * current [radix] (switching base rewrites them, [withRadix]); float literals
 * are decimal strings that may carry `.` / `e` / `inf` / `nan`.
 */
data class ProgDoc(
    val toks: List<ProgTok> = emptyList(),
    val radix: Radix = Radix.HEX,
    val evaluated: Boolean = false,
) {
    val isEmpty: Boolean get() = toks.isEmpty()

    private val openParens: Int
        get() = toks.count { it is ProgTok.Open } - toks.count { it is ProgTok.Close }

    /** The expression can't be evaluated yet (but isn't wrong). */
    fun endsOpen(): Boolean {
        if (toks.isEmpty() || openParens != 0) return true
        return when (val last = toks.last()) {
            is ProgTok.Lit -> {
                val d = last.digits
                d.isEmpty() || d == "-" ||
                    d.endsWith('.') || d.endsWith('e') || d.endsWith("e-") || d.endsWith("e+")
            }
            is ProgTok.Bin, ProgTok.Not, ProgTok.Open -> true
            ProgTok.Close -> false
        }
    }

    // --- rendering -------------------------------------------------------

    fun display(): String {
        val sb = StringBuilder()
        for ((i, t) in toks.withIndex()) {
            if (i > 0 && spaced(toks[i - 1], t)) sb.append(' ')
            when (t) {
                is ProgTok.Lit -> sb.append(t.digits)
                is ProgTok.Bin -> sb.append(t.op.display)
                ProgTok.Not -> sb.append("NOT")
                ProgTok.Open -> sb.append('(')
                ProgTok.Close -> sb.append(')')
            }
        }
        return sb.toString()
    }

    /** ASCII the Rust engine can lex, with a base prefix on every int literal. */
    fun engineInput(): String {
        val sb = StringBuilder()
        for (t in toks) when (t) {
            is ProgTok.Lit -> {
                val neg = t.digits.startsWith("-")
                val body = if (neg) t.digits.substring(1) else t.digits
                if (neg) sb.append('-')
                if (body.isNotEmpty()) sb.append(radix.enginePrefix).append(body)
            }
            is ProgTok.Bin -> sb.append(t.op.engine)
            ProgTok.Not -> sb.append('~')
            ProgTok.Open -> sb.append('(')
            ProgTok.Close -> sb.append(')')
        }
        return sb.toString()
    }

    // --- editing --------------------------------------------------------

    private fun fresh(): ProgDoc = if (evaluated) ProgDoc(radix = radix) else this
    private fun add(t: ProgTok) = copy(toks = toks + t, evaluated = false)
    private fun swapLast(t: ProgTok) = copy(toks = toks.dropLast(1) + t, evaluated = false)
    private fun dropLast() = copy(toks = toks.dropLast(1), evaluated = false)

    fun digit(c: Char): ProgDoc {
        if (!radix.accepts(c)) return this
        val b = fresh()
        return when (val last = b.toks.lastOrNull()) {
            is ProgTok.Lit -> b.swapLast(ProgTok.Lit(grow(last.digits, c)))
            null, is ProgTok.Bin, ProgTok.Not, ProgTok.Open -> b.add(ProgTok.Lit(c.toString()))
            ProgTok.Close -> this
        }
    }

    /** Decimal point — float mode only. */
    fun dot(): ProgDoc {
        val b = fresh()
        return when (val last = b.toks.lastOrNull()) {
            is ProgTok.Lit -> {
                val d = last.digits
                when {
                    d.contains('.') || d.contains('e') -> this
                    d.isEmpty() || d == "-" -> b.swapLast(ProgTok.Lit("${d}0."))
                    else -> b.swapLast(ProgTok.Lit("$d."))
                }
            }
            null, is ProgTok.Bin, ProgTok.Not, ProgTok.Open -> b.add(ProgTok.Lit("0."))
            ProgTok.Close -> this
        }
    }

    fun op(o: ProgOp): ProgDoc = when (val last = toks.lastOrNull()) {
        null -> if (o == ProgOp.SUB) add(ProgTok.Lit("-")) else this
        is ProgTok.Bin -> swapLast(ProgTok.Bin(o))
        ProgTok.Not -> this
        ProgTok.Open -> if (o == ProgOp.SUB) add(ProgTok.Lit("-")) else this
        is ProgTok.Lit ->
            if (last.digits.isEmpty() || last.digits == "-") this
            else copy(toks = toks + ProgTok.Bin(o), evaluated = false)
        ProgTok.Close -> copy(toks = toks + ProgTok.Bin(o), evaluated = false)
    }

    fun not(): ProgDoc {
        val b = fresh()
        return when (b.toks.lastOrNull()) {
            null, is ProgTok.Bin, ProgTok.Open -> b.add(ProgTok.Not)
            else -> this
        }
    }

    fun open(): ProgDoc {
        val b = fresh()
        return when (b.toks.lastOrNull()) {
            null, is ProgTok.Bin, ProgTok.Not, ProgTok.Open -> b.add(ProgTok.Open)
            else -> this
        }
    }

    fun close(): ProgDoc {
        if (openParens <= 0) return this
        val last = toks.lastOrNull()
        val ok = last is ProgTok.Close ||
            (last is ProgTok.Lit && last.digits.isNotEmpty() && last.digits != "-")
        return if (ok) add(ProgTok.Close) else this
    }

    /** Toggle the sign of the trailing literal (two's-complement on a result). */
    fun negate(): ProgDoc {
        val last = toks.lastOrNull() as? ProgTok.Lit ?: return this
        val d = last.digits
        val flipped = if (d.startsWith("-")) d.substring(1) else "-$d"
        return swapLast(ProgTok.Lit(flipped))
    }

    fun delete(): ProgDoc {
        val last = toks.lastOrNull() ?: return this
        if (last is ProgTok.Lit) {
            val d = last.digits.dropLast(1)
            return if (d.isEmpty() || d == "-") dropLast() else swapLast(ProgTok.Lit(d))
        }
        return dropLast()
    }

    fun cleared(): ProgDoc = ProgDoc(radix = radix)

    /** Re-express every integer literal in [next] (masked to [width] bits). */
    fun withRadix(next: Radix, width: Int, signed: Boolean = true): ProgDoc {
        if (next == radix) return this
        val rewritten = toks.map { t ->
            if (t is ProgTok.Lit) ProgTok.Lit(convert(t.digits, radix, next, width, signed)) else t
        }
        return copy(toks = rewritten, radix = next)
    }

    private fun grow(cur: String, c: Char): String {
        val neg = cur.startsWith("-")
        val body = if (neg) cur.substring(1) else cur
        val replaceLeadingZero = body == "0" && c != '.'
        val grown = (if (replaceLeadingZero) c.toString() else body + c).take(MAX_DIGITS)
        return if (neg) "-$grown" else grown
    }

    companion object {
        const val MAX_DIGITS = 72
        private const val SEP = "\u001F"

        /** A single evaluated literal holding [bits] shown in [radix]. */
        fun ofValue(bits: Long, radix: Radix, width: Int, signed: Boolean = true): ProgDoc =
            ProgDoc(
                listOf(ProgTok.Lit(ProgFormat.digitsOf(bits, radix, width, signed))),
                radix,
                evaluated = true,
            )

        /** A single evaluated literal holding the raw text [s] (float reentry). */
        fun ofLiteral(s: String, radix: Radix): ProgDoc =
            ProgDoc(listOf(ProgTok.Lit(s)), radix, evaluated = true)

        private fun convert(digits: String, from: Radix, to: Radix, width: Int, signed: Boolean): String {
            if (digits.isEmpty() || digits == "-") return digits
            val neg = digits.startsWith("-")
            val body = if (neg) digits.substring(1) else digits
            val parsed = try {
                if (from == Radix.DEC) body.toLong()
                else java.lang.Long.parseUnsignedLong(body, from.base)
            } catch (_: NumberFormatException) {
                return if (neg) "-0" else "0"
            }
            val mask = if (width >= 64) -1L else (1L shl width) - 1L
            val v = (if (neg) -parsed else parsed) and mask
            return ProgFormat.digitsOf(v, to, width, signed)
        }

        fun serialize(doc: ProgDoc): String = doc.toks.joinToString(SEP) { t ->
            when (t) {
                is ProgTok.Lit -> "L" + t.digits
                is ProgTok.Bin -> "O" + t.op.name
                ProgTok.Not -> "~"
                ProgTok.Open -> "("
                ProgTok.Close -> ")"
            }
        }

        fun deserialize(s: String, radix: Radix): ProgDoc {
            if (s.isEmpty()) return ProgDoc(radix = radix)
            val toks = s.split(SEP).mapNotNull { p ->
                when {
                    p.startsWith("L") -> ProgTok.Lit(p.substring(1))
                    p.startsWith("O") ->
                        runCatching { ProgOp.valueOf(p.substring(1)) }.getOrNull()?.let { ProgTok.Bin(it) }
                    p == "~" -> ProgTok.Not
                    p == "(" -> ProgTok.Open
                    p == ")" -> ProgTok.Close
                    else -> null
                }
            }
            val single = toks.size == 1 && toks[0] is ProgTok.Lit
            return ProgDoc(toks, radix, evaluated = single)
        }
    }
}

private fun spaced(a: ProgTok, b: ProgTok): Boolean =
    a is ProgTok.Bin || b is ProgTok.Bin || a == ProgTok.Not || b == ProgTok.Not
