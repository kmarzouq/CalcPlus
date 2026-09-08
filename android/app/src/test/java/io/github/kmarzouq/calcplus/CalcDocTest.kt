// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Input-rule tests for [CalcDoc]. Pure JVM — no engine, no Android. */
class CalcDocTest {

    private fun doc(vararg keys: Key): CalcDoc =
        keys.fold(CalcDoc()) { d, k -> d.press(k) }

    @Test fun digitsAppend() {
        assertEquals("123", doc(Key.D1, Key.D2, Key.D3).expr)
    }

    @Test fun operatorsUseDisplayGlyphs() {
        assertEquals("1+2", doc(Key.D1, Key.ADD, Key.D2).expr)
        assertEquals("6÷2", doc(Key.D6, Key.DIV, Key.D2).expr)
        assertEquals("6×2", doc(Key.D6, Key.MUL, Key.D2).expr)
        assertEquals("6−2", doc(Key.D6, Key.SUB, Key.D2).expr)
    }

    @Test fun danglingOperatorIsReplaced() {
        assertEquals("5×", doc(Key.D5, Key.ADD, Key.SUB, Key.MUL).expr)
    }

    @Test fun leadingMinusAllowedNothingElseIs() {
        assertEquals("−", doc(Key.SUB).expr)
        assertEquals("", doc(Key.ADD).expr)
        assertEquals("", doc(Key.MUL).expr)
    }

    @Test fun oneDotPerNumber() {
        assertEquals("1.5", doc(Key.D1, Key.DOT, Key.D5).expr)
        assertEquals("1.5", doc(Key.D1, Key.DOT, Key.D5, Key.DOT).expr)
        assertEquals("1.5+0.", doc(Key.D1, Key.DOT, Key.D5, Key.ADD, Key.DOT).expr)
    }

    @Test fun percentOnlyAfterValue() {
        assertEquals("50%", doc(Key.D5, Key.D0, Key.PCT).expr)
        assertEquals("", doc(Key.PCT).expr)
    }

    @Test fun negateTogglesCurrentNumber() {
        assertEquals("−5", doc(Key.D5, Key.NEG).expr)
        assertEquals("5", doc(Key.D5, Key.NEG, Key.NEG).expr)
        assertEquals("3+−5", doc(Key.D3, Key.ADD, Key.D5, Key.NEG).expr)
    }

    @Test fun deleteRemovesLastCharAndImplicitTimes() {
        assertEquals("12", doc(Key.D1, Key.D2, Key.D3, Key.DELETE).expr)
        // "(" after a digit inserts "×(", delete should remove both
        val d = CalcDoc("5").press(Key.LPAREN)
        assertEquals("5×(", d.expr)
        assertEquals("5", d.press(Key.DELETE).expr)
    }

    @Test fun clearEmpties() {
        assertEquals("", doc(Key.D9, Key.D9, Key.CLEAR).expr)
    }

    @Test fun typingDigitAfterEqualsStartsFresh() {
        val evaluated = CalcDoc("42", evaluated = true)
        assertEquals("7", evaluated.press(Key.D7).expr)
    }

    @Test fun typingOperatorAfterEqualsContinues() {
        val evaluated = CalcDoc("42", evaluated = true)
        assertEquals("42+", evaluated.press(Key.ADD).expr)
    }

    @Test fun asciiExpressionNormalizes() {
        assertTrue(doc(Key.D6, Key.DIV, Key.D2).asciiExpression == "6/2")
    }

    @Test fun functionKeysInsertOpenCall() {
        assertEquals("sin(", doc(Key.SIN).expr)
        assertEquals("sin(30", doc(Key.SIN, Key.D3, Key.D0).expr)
        assertEquals("√(", doc(Key.SQRT).expr)
        assertEquals("sqrt(9", doc(Key.SQRT, Key.D9).asciiExpression)
    }

    @Test fun impliedTimesBeforeFunctionsAndConstants() {
        assertEquals("2×sin(", doc(Key.D2, Key.SIN).expr)
        assertEquals("2×π", doc(Key.D2, Key.PI).expr)
        assertEquals("2*pi", doc(Key.D2, Key.PI).asciiExpression)
    }

    @Test fun suffixKeysOnlyAfterValue() {
        assertEquals("5!", doc(Key.D5, Key.FACT).expr)
        assertEquals("5^-1", doc(Key.D5, Key.RECIP).expr)
        assertEquals("", doc(Key.FACT).expr)
    }

    @Test fun deleteRemovesWholeFunctionToken() {
        assertEquals("", doc(Key.SIN, Key.DELETE).expr)
        assertEquals("2", doc(Key.D2, Key.SIN, Key.DELETE).expr)
    }
}
