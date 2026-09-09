// SPDX-License-Identifier: GPL-2.0-only
//! Fixed-width integer expression engine for the programmer calculator.
//!
//! Values are two's-complement bit patterns of a chosen [`Width`] (8/16/32/64).
//! Arithmetic runs in `u128` with wrapping semantics and is masked back to the
//! width after every step, so every operation wraps exactly as it would in a
//! hardware register. `/` and `%` interpret their operands as **signed** for
//! that width.
//!
//! Grammar (lowest precedence first), C-flavoured:
//! ```text
//!   or  nor            |        keyword: or  nor
//!   xor xnor           ^        keyword: xor xnor
//!   and nand           &        keyword: and nand
//!   shift / rotate     << >>    keyword: shl shr rol ror
//!   add / sub          + -
//!   mul / div / rem    * / %    keyword: mod
//!   prefix             - ~      keyword: not
//!   atom               literal | ( expr )
//! ```
//! Literals: decimal `42`, hex `0x2A`, octal `0o52`, binary `0b101010`, with
//! `_` group separators allowed. A leading `-` is the prefix operator.

use crate::error::CalcError;
use alloc::boxed::Box;
use alloc::string::String;
use alloc::vec::Vec;

/// Register width for the programmer calculator.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Width {
    W8,
    W16,
    W32,
    W64,
}

impl Width {
    /// `0 -> 8-bit, 1 -> 16, 2 -> 32, anything else -> 64` (the FFI wire form).
    #[must_use]
    pub fn from_code(code: i32) -> Self {
        match code {
            0 => Self::W8,
            1 => Self::W16,
            2 => Self::W32,
            _ => Self::W64,
        }
    }

    fn bits(self) -> u32 {
        match self {
            Self::W8 => 8,
            Self::W16 => 16,
            Self::W32 => 32,
            Self::W64 => 64,
        }
    }

    /// Low-`bits` ones.
    fn mask(self) -> u128 {
        match self {
            Self::W64 => u64::MAX as u128,
            other => (1u128 << other.bits()) - 1,
        }
    }
}

/// Evaluate `input` as a fixed-width integer expression.
///
/// Returns the result's bit pattern (already masked to `width`). Never panics;
/// every failure is a [`CalcError`].
pub fn evaluate(input: &str, width: Width) -> Result<u64, CalcError> {
    let toks = lex(input)?;
    if toks.is_empty() {
        return Err(CalcError::UnexpectedEnd);
    }
    let mut p = Parser { toks, i: 0 };
    let expr = p.expr(0)?;
    if let Some(tok) = p.toks.get(p.i) {
        return Err(CalcError::Syntax { pos: tok.1 });
    }
    let value = eval(&expr, width)?;
    Ok((value & width.mask()) as u64)
}

// --- lexer -------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
enum Tok {
    Int(u128),
    Plus,
    Minus,
    Star,
    Slash,
    Percent,
    Amp,
    Pipe,
    Caret,
    Tilde,
    Shl,
    Shr,
    LParen,
    RParen,
    Kw(Kw),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kw {
    And,
    Or,
    Xor,
    Not,
    Nand,
    Nor,
    Xnor,
    Mod,
    Shl,
    Shr,
    Rol,
    Ror,
}

type Chars<'a> = core::iter::Peekable<core::str::CharIndices<'a>>;

fn lex(src: &str) -> Result<Vec<(Tok, usize)>, CalcError> {
    let mut out: Vec<(Tok, usize)> = Vec::new();
    let mut chars = src.char_indices().peekable();

    while let Some(&(pos, ch)) = chars.peek() {
        let tok = match ch {
            c if c.is_whitespace() => {
                chars.next();
                continue;
            }
            '+' => one(&mut chars, Tok::Plus),
            '-' | '\u{2212}' => one(&mut chars, Tok::Minus),
            '*' | '\u{00D7}' | '\u{22C5}' => one(&mut chars, Tok::Star),
            '/' | '\u{00F7}' => one(&mut chars, Tok::Slash),
            '%' => one(&mut chars, Tok::Percent),
            '&' => one(&mut chars, Tok::Amp),
            '|' => one(&mut chars, Tok::Pipe),
            '^' => one(&mut chars, Tok::Caret),
            '~' => one(&mut chars, Tok::Tilde),
            '(' => one(&mut chars, Tok::LParen),
            ')' => one(&mut chars, Tok::RParen),
            '\u{00AB}' => one(&mut chars, Tok::Shl), // «
            '\u{00BB}' => one(&mut chars, Tok::Shr), // »
            '<' => {
                chars.next();
                match chars.peek() {
                    Some(&(_, '<')) => {
                        chars.next();
                        Tok::Shl
                    }
                    _ => return Err(CalcError::BadChar { pos, ch }),
                }
            }
            '>' => {
                chars.next();
                match chars.peek() {
                    Some(&(_, '>')) => {
                        chars.next();
                        Tok::Shr
                    }
                    _ => return Err(CalcError::BadChar { pos, ch }),
                }
            }
            '0'..='9' => Tok::Int(lex_number(&mut chars)?),
            c if c.is_ascii_alphabetic() || c == '_' => {
                let word = lex_word(&mut chars);
                Tok::Kw(keyword(&word)?)
            }
            _ => return Err(CalcError::BadChar { pos, ch }),
        };
        out.push((tok, pos));
    }
    Ok(out)
}

fn one(chars: &mut Chars<'_>, tok: Tok) -> Tok {
    chars.next();
    tok
}

fn keyword(word: &str) -> Result<Kw, CalcError> {
    Ok(match word {
        "and" => Kw::And,
        "or" => Kw::Or,
        "xor" => Kw::Xor,
        "not" => Kw::Not,
        "nand" => Kw::Nand,
        "nor" => Kw::Nor,
        "xnor" => Kw::Xnor,
        "mod" => Kw::Mod,
        "shl" => Kw::Shl,
        "shr" => Kw::Shr,
        "rol" => Kw::Rol,
        "ror" => Kw::Ror,
        _ => return Err(CalcError::UnknownName(String::from(word))),
    })
}

fn lex_word(chars: &mut Chars<'_>) -> String {
    let mut s = String::new();
    while let Some(&(_, c)) = chars.peek() {
        if c.is_ascii_alphanumeric() || c == '_' {
            s.push(c.to_ascii_lowercase());
            chars.next();
        } else {
            break;
        }
    }
    s
}

/// `0x..`, `0b..`, `0o..`, or plain decimal, with `_` separators. The leading
/// digit has already been peeked (not consumed).
fn lex_number(chars: &mut Chars<'_>) -> Result<u128, CalcError> {
    let Some((pos0, first)) = chars.next() else {
        return Err(CalcError::UnexpectedEnd);
    };

    let (base, mut acc, mut seen): (u128, u128, bool) = if first == '0' {
        match chars.peek() {
            Some(&(_, 'x' | 'X')) => {
                chars.next();
                (16, 0, false)
            }
            Some(&(_, 'b' | 'B')) => {
                chars.next();
                (2, 0, false)
            }
            Some(&(_, 'o' | 'O')) => {
                chars.next();
                (8, 0, false)
            }
            _ => (10, 0, true),
        }
    } else {
        (10, u128::from(first as u32 - '0' as u32), true)
    };

    while let Some(&(dpos, c)) = chars.peek() {
        if c == '_' {
            chars.next();
            continue;
        }
        let Some(d) = c.to_digit(base as u32) else {
            if c.is_ascii_alphanumeric() {
                return Err(CalcError::BadChar { pos: dpos, ch: c });
            }
            break;
        };
        acc = acc
            .checked_mul(base)
            .and_then(|a| a.checked_add(u128::from(d)))
            .ok_or(CalcError::Overflow)?;
        seen = true;
        chars.next();
    }

    if seen {
        Ok(acc)
    } else {
        Err(CalcError::Syntax { pos: pos0 })
    }
}

// --- parser ----------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Op {
    Or,
    Nor,
    Xor,
    Xnor,
    And,
    Nand,
    Shl,
    Shr,
    Rol,
    Ror,
    Add,
    Sub,
    Mul,
    Div,
    Rem,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Node {
    Lit(u128),
    Neg(Box<Node>),
    Not(Box<Node>),
    Bin(Op, Box<Node>, Box<Node>),
}

struct Parser {
    toks: Vec<(Tok, usize)>,
    i: usize,
}

const PREFIX_BP: u8 = 70;

impl Parser {
    fn peek(&self) -> Option<&Tok> {
        self.toks.get(self.i).map(|t| &t.0)
    }

    fn bump(&mut self) -> Option<(Tok, usize)> {
        let t = self.toks.get(self.i).cloned();
        if t.is_some() {
            self.i += 1;
        }
        t
    }

    fn expr(&mut self, min_bp: u8) -> Result<Node, CalcError> {
        let mut lhs = self.prefix()?;
        while let Some(tok) = self.peek() {
            let Some((op, lbp, rbp)) = infix(tok) else {
                break;
            };
            if lbp < min_bp {
                break;
            }
            self.i += 1;
            let rhs = self.expr(rbp)?;
            lhs = Node::Bin(op, Box::new(lhs), Box::new(rhs));
        }
        Ok(lhs)
    }

    fn prefix(&mut self) -> Result<Node, CalcError> {
        let Some((tok, pos)) = self.bump() else {
            return Err(CalcError::UnexpectedEnd);
        };
        match tok {
            Tok::Int(v) => Ok(Node::Lit(v)),
            Tok::Minus => Ok(Node::Neg(Box::new(self.expr(PREFIX_BP)?))),
            Tok::Plus => self.expr(PREFIX_BP),
            Tok::Tilde | Tok::Kw(Kw::Not) => Ok(Node::Not(Box::new(self.expr(PREFIX_BP)?))),
            Tok::LParen => {
                let inner = self.expr(0)?;
                match self.bump() {
                    Some((Tok::RParen, _)) => Ok(inner),
                    Some((_, p)) => Err(CalcError::Syntax { pos: p }),
                    None => Err(CalcError::UnexpectedEnd),
                }
            }
            _ => Err(CalcError::Syntax { pos }),
        }
    }
}

/// `(op, left binding power, right binding power)`; all left-associative.
fn infix(t: &Tok) -> Option<(Op, u8, u8)> {
    let (op, lbp) = match t {
        Tok::Pipe => (Op::Or, 10),
        Tok::Kw(Kw::Or) => (Op::Or, 10),
        Tok::Kw(Kw::Nor) => (Op::Nor, 10),
        Tok::Caret => (Op::Xor, 20),
        Tok::Kw(Kw::Xor) => (Op::Xor, 20),
        Tok::Kw(Kw::Xnor) => (Op::Xnor, 20),
        Tok::Amp => (Op::And, 30),
        Tok::Kw(Kw::And) => (Op::And, 30),
        Tok::Kw(Kw::Nand) => (Op::Nand, 30),
        Tok::Shl | Tok::Kw(Kw::Shl) => (Op::Shl, 40),
        Tok::Shr | Tok::Kw(Kw::Shr) => (Op::Shr, 40),
        Tok::Kw(Kw::Rol) => (Op::Rol, 40),
        Tok::Kw(Kw::Ror) => (Op::Ror, 40),
        Tok::Plus => (Op::Add, 50),
        Tok::Minus => (Op::Sub, 50),
        Tok::Star => (Op::Mul, 60),
        Tok::Slash => (Op::Div, 60),
        Tok::Percent | Tok::Kw(Kw::Mod) => (Op::Rem, 60),
        _ => return None,
    };
    Some((op, lbp, lbp + 1))
}

// --- evaluator -----------------------------------------------------------

fn eval(node: &Node, w: Width) -> Result<u128, CalcError> {
    let mask = w.mask();
    Ok(match node {
        Node::Lit(v) => v & mask,
        Node::Neg(a) => (mask.wrapping_add(1).wrapping_sub(eval(a, w)?)) & mask,
        Node::Not(a) => !eval(a, w)? & mask,
        Node::Bin(op, a, b) => bin(*op, eval(a, w)?, eval(b, w)?, w)?,
    })
}

fn bin(op: Op, x: u128, y: u128, w: Width) -> Result<u128, CalcError> {
    let mask = w.mask();
    let bits = w.bits();
    Ok(match op {
        Op::Add => x.wrapping_add(y) & mask,
        Op::Sub => x.wrapping_sub(y) & mask,
        Op::Mul => x.wrapping_mul(y) & mask,
        Op::Div | Op::Rem => {
            let a = to_signed(x, w);
            let b = to_signed(y, w);
            if b == 0 {
                return Err(CalcError::DivisionByZero);
            }
            let r = if op == Op::Div {
                a.wrapping_div(b)
            } else {
                a.wrapping_rem(b)
            };
            (r as u128) & mask
        }
        Op::And => x & y & mask,
        Op::Nand => !(x & y) & mask,
        Op::Or => (x | y) & mask,
        Op::Nor => !(x | y) & mask,
        Op::Xor => (x ^ y) & mask,
        Op::Xnor => !(x ^ y) & mask,
        Op::Shl => match shift_amount(y, w) {
            Some(n) if n < bits => (x << n) & mask,
            _ => 0,
        },
        Op::Shr => match shift_amount(y, w) {
            Some(n) if n < bits => (x & mask) >> n,
            _ => 0,
        },
        Op::Rol | Op::Ror => {
            let x = x & mask;
            let step = to_signed(y, w).rem_euclid(i128::from(bits)) as u32;
            let n = if op == Op::Ror {
                (bits - step) % bits
            } else {
                step % bits
            };
            if n == 0 {
                x
            } else {
                ((x << n) | (x >> (bits - n))) & mask
            }
        }
    })
}

/// Interpret `v` (a `width`-bit pattern) as a signed two's-complement integer.
fn to_signed(v: u128, w: Width) -> i128 {
    let v = v & w.mask();
    let sign = 1u128 << (w.bits() - 1);
    if v & sign != 0 {
        (v as i128) - (1i128 << w.bits())
    } else {
        v as i128
    }
}

/// A non-negative shift amount, or `None` if the operand is negative.
fn shift_amount(y: u128, w: Width) -> Option<u32> {
    let s = to_signed(y, w);
    if s < 0 {
        None
    } else if s >= i128::from(u32::MAX) {
        Some(u32::MAX)
    } else {
        Some(s as u32)
    }
}

#[cfg(test)]
mod tests {
    use super::Width::{W16, W32, W64, W8};
    use super::*;

    fn ev(s: &str, w: Width) -> u64 {
        evaluate(s, w).unwrap_or_else(|e| panic!("`{s}` failed: {e:?}"))
    }

    #[test]
    fn literals_in_every_base() {
        assert_eq!(ev("42", W8), 42);
        assert_eq!(ev("0x2A", W8), 42);
        assert_eq!(ev("0o52", W8), 42);
        assert_eq!(ev("0b101010", W8), 42);
        assert_eq!(ev("0xFF_FF", W32), 0xFFFF);
    }

    #[test]
    fn arithmetic_wraps_to_width() {
        assert_eq!(ev("0xFF + 1", W8), 0);
        assert_eq!(ev("0xFF + 1", W16), 0x100);
        assert_eq!(ev("0 - 1", W8), 0xFF);
        assert_eq!(ev("0xFFFF * 0xFFFF", W32), 0xFFFE_0001);
        assert_eq!(ev("2 + 3 * 4", W8), 14); // precedence
        assert_eq!(ev("(2 + 3) * 4", W8), 20);
    }

    #[test]
    fn signed_division_and_remainder() {
        assert_eq!(ev("0xFF / 2", W8), 0); // -1 / 2 == 0
        assert_eq!(ev("0xFC / 2", W8), 0xFE); // -4 / 2 == -2
        assert_eq!(ev("10 mod 3", W8), 1);
        assert_eq!(ev("0xF9 mod 3", W8), 0xFF); // -7 % 3 == -1
        assert!(matches!(
            evaluate("1 / 0", W8),
            Err(CalcError::DivisionByZero)
        ));
        assert!(matches!(
            evaluate("5 mod 0", W8),
            Err(CalcError::DivisionByZero)
        ));
    }

    #[test]
    fn bitwise() {
        assert_eq!(ev("0b1010 & 0b1100", W8), 0b1000);
        assert_eq!(ev("0b1010 | 0b0101", W8), 0b1111);
        assert_eq!(ev("0b1010 ^ 0b0110", W8), 0b1100);
        assert_eq!(ev("5 xor 3", W8), 6);
        assert_eq!(ev("~0", W8), 0xFF);
        assert_eq!(ev("~0", W16), 0xFFFF);
        assert_eq!(ev("not 0xF0", W8), 0x0F);
        assert_eq!(ev("5 nand 3", W8), 0xFE);
        assert_eq!(ev("5 nor 2", W8), 0xF8);
        assert_eq!(ev("0xFF xnor 0x0F", W8), 0x0F);
    }

    #[test]
    fn shifts_and_rotates() {
        assert_eq!(ev("1 << 4", W8), 16);
        assert_eq!(ev("1 << 8", W8), 0); // shifted clean out
        assert_eq!(ev("1 shl 100", W8), 0);
        assert_eq!(ev("0x80 >> 3", W8), 0x10); // logical, zero-fill
        assert_eq!(ev("0xF0 rol 4", W8), 0x0F);
        assert_eq!(ev("0x0F ror 4", W8), 0xF0);
        assert_eq!(ev("0x1234 rol 8", W16), 0x3412);
        assert_eq!(ev("1 ror 1", W8), 0x80);
    }

    #[test]
    fn precedence_bitwise_below_shift_below_arith() {
        // and(0xFF, shl(1, add(2, 1))) = 0xFF & (1 << 3) = 8
        assert_eq!(ev("0xFF & 1 << 2 + 1", W8), 8);
        // or binds loosest
        assert_eq!(ev("0x80 | 0x0F & 0x03", W8), 0x83);
    }

    #[test]
    fn negative_literals() {
        assert_eq!(ev("-1", W8), 0xFF);
        assert_eq!(ev("-1", W32), 0xFFFF_FFFF);
        assert_eq!(ev("-(2 + 2)", W8), 0xFC);
        assert_eq!(ev("3 - -2", W8), 5);
    }

    #[test]
    fn structural_errors() {
        assert!(matches!(evaluate("1 +", W8), Err(CalcError::UnexpectedEnd)));
        assert!(matches!(evaluate("", W8), Err(CalcError::UnexpectedEnd)));
        assert!(matches!(evaluate("1 2", W8), Err(CalcError::Syntax { .. })));
        assert!(matches!(
            evaluate("(1 + 2", W8),
            Err(CalcError::UnexpectedEnd)
        ));
        assert!(matches!(
            evaluate("0b12", W8),
            Err(CalcError::BadChar { .. })
        ));
        assert!(matches!(
            evaluate("frob", W8),
            Err(CalcError::UnknownName(_))
        ));
        assert!(matches!(
            evaluate("1 < 2", W8),
            Err(CalcError::BadChar { .. })
        ));
    }

    #[test]
    fn overflow_on_absurd_literal() {
        let huge = "0x1".to_string() + &"0".repeat(40);
        assert!(matches!(evaluate(&huge, W64), Err(CalcError::Overflow)));
    }
}
