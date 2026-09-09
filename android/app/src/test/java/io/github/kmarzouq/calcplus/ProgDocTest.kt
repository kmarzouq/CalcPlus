// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Input-rule / formatting tests for the programmer calculator. Pure JVM. */
class ProgDocTest {

    private val w = WordSize.QWORD

    private fun hex(vararg cs: Char): ProgDoc =
        cs.fold(ProgDoc(radix = Radix.HEX)) { d, c -> d.digit(c) }

    @Test fun hexDigitsBuildOneLiteral() {
        val d = hex('1', 'A')
        assertEquals("1A", d.display())
        assertEquals("0x1A", d.engineInput())
    }

    @Test fun rejectsDigitsOutsideBase() {
        val bin = ProgDoc(radix = Radix.BIN).digit('1').digit('0').digit('2')
        assertEquals("10", bin.display())
        assertEquals("0b10", bin.engineInput())
    }

    @Test fun operatorsRenderWordsAndSymbols() {
        val d = hex('F', 'F').op(ProgOp.AND).digit('0').digit('F')
        assertEquals("FF AND F", d.display()) // leading zero dropped
        assertEquals("0xFF and 0xF", d.engineInput())
    }

    @Test fun danglingOperatorIsSwapped() {
        val d = hex('5').op(ProgOp.ADD).op(ProgOp.XOR)
        assertEquals("5 XOR", d.display())
        assertTrue(d.endsOpen())
    }

    @Test fun shiftAndParens() {
        val d = ProgDoc(radix = Radix.HEX)
            .open().digit('F').digit('F').op(ProgOp.SHL).digit('8').close()
        assertEquals("(FF << 8)", d.display())
        assertEquals("(0xFF<<0x8)", d.engineInput())
        assertFalse(d.endsOpen())
    }

    @Test fun unbalancedParensIsOpen() {
        assertTrue(ProgDoc(radix = Radix.HEX).open().digit('1').endsOpen())
    }

    @Test fun negateTogglesLiteralSign() {
        val d = hex('A').negate()
        assertEquals("-A", d.display())
        assertEquals("-0xA", d.engineInput())
        assertEquals("A", d.negate().display())
    }

    @Test fun switchingBaseRewritesLiterals() {
        val d = hex('F', 'F').op(ProgOp.OR).digit('1')
        val dec = d.withRadix(Radix.DEC, w)
        assertEquals("255 OR 1", dec.display())
        assertEquals("255 or 1", dec.engineInput())
        val bin = dec.withRadix(Radix.BIN, w)
        assertEquals("11111111 OR 1", bin.display())
        assertEquals("0b11111111 or 0b1", bin.engineInput())
    }

    @Test fun switchingBaseUsesTwosComplementForNegatives() {
        // -1 in DEC, byte width, becomes FF in hex
        val d = ProgDoc(radix = Radix.DEC).digit('1').negate()
        assertEquals("-1", d.display())
        val hex = d.withRadix(Radix.HEX, WordSize.BYTE)
        assertEquals("FF", hex.display())
    }

    @Test fun serializeRoundTrips() {
        val d = hex('A', 'B').op(ProgOp.ROL).digit('4')
        val back = ProgDoc.deserialize(ProgDoc.serialize(d), Radix.HEX)
        assertEquals(d.display(), back.display())
        assertEquals(d.engineInput(), back.engineInput())
    }

    @Test fun formatSignExtendsForWidth() {
        assertEquals(-1L, ProgFormat.signExtend(0xFFL, WordSize.BYTE))
        assertEquals(127L, ProgFormat.signExtend(0x7FL, WordSize.BYTE))
        assertEquals(255L, ProgFormat.signExtend(0xFFL, WordSize.WORD))
    }

    @Test fun groupedFormatting() {
        assertEquals("FFAB", ProgFormat.grouped(0xFFABL, Radix.HEX, WordSize.QWORD))
        assertEquals("FF FFAB", ProgFormat.grouped(0xFFFFABL, Radix.HEX, WordSize.QWORD))
        assertEquals("1010 1011", ProgFormat.grouped(0xABL, Radix.BIN, WordSize.QWORD))
        assertEquals("-96", ProgFormat.grouped(0xA0L, Radix.DEC, WordSize.BYTE))
    }
}
