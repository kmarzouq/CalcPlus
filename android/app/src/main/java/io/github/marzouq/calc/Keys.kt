package io.github.marzouq.calc

/**
 * Every button the calculator can emit. Shared by the activity keypad and the
 * widget keypad so the input rules live in exactly one place ([CalcDoc]).
 */
enum class Key {
    // digits + basic
    D0, D1, D2, D3, D4, D5, D6, D7, D8, D9,
    DOT,
    ADD, SUB, MUL, DIV, POW, PCT,
    LPAREN, RPAREN,
    NEG,       // ± toggle sign of the current number
    DELETE,    // ⌫
    CLEAR,     // C / AC
    EQUALS,    // = (evaluation is driven by the caller, not CalcDoc)

    // scientific (activity only)
    SIN, COS, TAN, ASIN, ACOS, ATAN,
    LN, LOG, SQRT, FACT, RECIP,
    PI, EULER;

    companion object {
        /** Parse the token name stored in a PendingIntent extra. */
        fun fromName(name: String?): Key? =
            entries.firstOrNull { it.name == name }
    }
}
