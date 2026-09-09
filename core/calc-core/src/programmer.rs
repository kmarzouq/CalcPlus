// SPDX-License-Identifier: GPL-2.0-only
//! Expression engine for the programmer calculator.
//!
//! A [`NumFormat`] chooses how numbers are interpreted:
//! * **signed int** — two's-complement, wraps to the [`Width`]
//! * **unsigned int** — same bit-twiddling, unsigned `/` `%` and display
//! * **float** — ordinary `f64` arithmetic, then rounded (ties to even) to the
//!   nearest value of an IEEE-style `sign` / `exp` / `mant` layout. Standard
//!   `f32` and `f64` layouts use the native conversion; anything else runs a
//!   generic minifloat encoder.
//!
//! Integer arithmetic runs in `u128` with wrapping semantics, masked to the
//! width after every step, so it wraps exactly as a hardware register would.
//!
//! Grammar (lowest precedence first), C-flavoured:
//! ```text
//!   or  nor            |        keyword: or  nor       (int only)
//!   xor xnor           ^        keyword: xor xnor      (int only)
//!   and nand           &        keyword: and nand      (int only)
//!   shift / rotate     << >>    keyword: shl shr rol ror  (int only)
//!   add / sub          + -
//!   mul / div / rem    * / %    keyword: mod
//!   prefix             - ~      keyword: not  (~/not int only)
//!   atom               literal | ( expr )
//! ```
//! Literals: decimal `42` / `1.5` / `6.02e23`, hex `0x2A`, octal `0o52`,
//! binary `0b101010`, with `_` separators. A leading `-` is the prefix operator.

use crate::error::CalcError;
use alloc::boxed::Box;
use alloc::string::{String, ToString};
use alloc::vec::Vec;

/// Register width for integer modes.
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

/// How the programmer calculator interprets its numbers.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum NumFormat {
    /// Two's-complement (signed) or plain unsigned integer of a fixed width.
    Int { width: Width, signed: bool },
    /// IEEE-754-style float with `sign` (0 or 1), `exp` and `mant` bit fields.
    /// `sign + exp + mant` never exceeds 64. Bias is `2^(exp-1) - 1`.
    Float { sign: u32, exp: u32, mant: u32 },
}

impl NumFormat {
    /// Decode the FFI wire form. `mode`: 0 = signed int, 1 = unsigned int,
    /// 2 = float. For int, `p1` is the [`Width`] code. For float, `p1` is the
    /// sign-bit count, `p2` the exponent width, `p3` the mantissa width.
    #[must_use]
    pub fn from_wire(mode: i32, p1: i32, p2: i32, p3: i32) -> Self {
        match mode {
            2 => {
                let sign = p1.clamp(0, 1) as u32;
                let exp = p2.clamp(2, 30) as u32;
                let head = sign + exp;
                let mant = (p3.max(1) as u32)
                    .min(52)
                    .min(64u32.saturating_sub(head).max(1));
                Self::Float { sign, exp, mant }
            }
            m => Self::Int {
                width: Width::from_code(p1),
                signed: m == 0,
            },
        }
    }

    fn total_bits(self) -> u32 {
        match self {
            Self::Int { width, .. } => width.bits(),
            Self::Float { sign, exp, mant } => sign + exp + mant,
        }
    }

    fn mask(self) -> u64 {
        match self.total_bits() {
            64 => u64::MAX,
            b => (1u64 << b) - 1,
        }
    }
}

/// Evaluate `input` as a signed fixed-width integer expression (compat shim).
///
/// Returns the result's bit pattern, masked to `width`. Never panics.
pub fn evaluate(input: &str, width: Width) -> Result<u64, CalcError> {
    evaluate_fmt(
        input,
        NumFormat::Int {
            width,
            signed: true,
        },
    )
}

/// Evaluate `input` under `fmt`, returning the result's raw bit pattern.
///
/// Integer modes wrap to the width; float mode rounds the `f64` result to the
/// nearest value representable in the chosen layout. Never panics.
pub fn evaluate_fmt(input: &str, fmt: NumFormat) -> Result<u64, CalcError> {
    let toks = lex(input)?;
    if toks.is_empty() {
        return Err(CalcError::UnexpectedEnd);
    }
    let mut p = Parser { toks, i: 0 };
    let node = p.expr(0)?;
    if let Some(tok) = p.toks.get(p.i) {
        return Err(CalcError::Syntax { pos: tok.1 });
    }
    match fmt {
        NumFormat::Int { width, signed } => {
            let value = eval_int(&node, width, signed)?;
            Ok((value & width.mask()) as u64)
        }
        NumFormat::Float { sign, exp, mant } => {
            let value = eval_float(&node)?;
            Ok(float_encode(value, sign, exp, mant))
        }
    }
}

/// Render `bits` as a human value string for the given `fmt` — a signed or
/// unsigned decimal, or the float's decimal value (`3.14159`, `-∞`, `NaN`).
#[must_use]
pub fn format_value(bits: u64, fmt: NumFormat) -> String {
    let bits = bits & fmt.mask();
    match fmt {
        NumFormat::Int { width, signed } => {
            if signed {
                to_signed(u128::from(bits), width).to_string()
            } else {
                bits.to_string()
            }
        }
        NumFormat::Float { sign, exp, mant } => {
            format_f64(float_decode(bits, sign, exp, mant), mant)
        }
    }
}

// --- lexer -------------------------------------------------------------------

/// A numeric literal — an exact integer, or a float if it had a `.` / exponent.
#[derive(Debug, Clone, Copy, PartialEq)]
enum LitVal {
    I(u128),
    F(f64),
}

#[derive(Debug, Clone, PartialEq)]
enum Tok {
    Num(LitVal),
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
            '0'..='9' | '.' => Tok::Num(lex_number(&mut chars)?),
            c if c.is_ascii_alphabetic() || c == '_' => {
                let word = lex_word(&mut chars);
                match word.as_str() {
                    "inf" | "infinity" => Tok::Num(LitVal::F(f64::INFINITY)),
                    "nan" => Tok::Num(LitVal::F(f64::NAN)),
                    _ => Tok::Kw(keyword(&word)?),
                }
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

/// `0x..` / `0b..` / `0o..` — an exact integer — or a base-10 number that may
/// carry a `.` fraction and/or an `e` exponent (making it a float). `_`
/// separators are allowed. The leading digit/`.` has not been consumed.
fn lex_number(chars: &mut Chars<'_>) -> Result<LitVal, CalcError> {
    let Some((pos0, first)) = chars.next() else {
        return Err(CalcError::UnexpectedEnd);
    };

    // Radix-prefixed integers: 0x / 0b / 0o.
    if first == '0' {
        let base: Option<u128> = match chars.peek() {
            Some(&(_, 'x' | 'X')) => Some(16),
            Some(&(_, 'b' | 'B')) => Some(2),
            Some(&(_, 'o' | 'O')) => Some(8),
            _ => None,
        };
        if let Some(base) = base {
            chars.next();
            let mut acc: u128 = 0;
            let mut seen = false;
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
            return if seen {
                Ok(LitVal::I(acc))
            } else {
                Err(CalcError::Syntax { pos: pos0 })
            };
        }
    }

    // Base-10: collect the text, then decide integer vs float.
    let mut text = String::new();
    text.push(first);
    let mut is_float = first == '.';
    while let Some(&(dpos, c)) = chars.peek() {
        match c {
            '0'..='9' => text.push(c),
            '_' => {}
            '.' if !is_float => {
                is_float = true;
                text.push('.');
            }
            'e' | 'E' if !text.contains(['e', 'E']) => {
                is_float = true;
                text.push('e');
                chars.next();
                if let Some(&(_, sgn @ ('+' | '-'))) = chars.peek() {
                    text.push(sgn);
                    chars.next();
                }
                continue;
            }
            c if c.is_ascii_alphanumeric() => return Err(CalcError::BadChar { pos: dpos, ch: c }),
            _ => break,
        }
        chars.next();
    }

    if is_float {
        text.parse::<f64>()
            .map(LitVal::F)
            .map_err(|_| CalcError::Syntax { pos: pos0 })
    } else {
        text.parse::<u128>()
            .map(LitVal::I)
            .map_err(|_| CalcError::Overflow)
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

#[derive(Debug, Clone, PartialEq)]
enum Node {
    Lit(LitVal),
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
            Tok::Num(v) => Ok(Node::Lit(v)),
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

// --- integer evaluator -------------------------------------------------

fn eval_int(node: &Node, w: Width, signed: bool) -> Result<u128, CalcError> {
    let mask = w.mask();
    Ok(match node {
        Node::Lit(LitVal::I(v)) => v & mask,
        Node::Lit(LitVal::F(_)) => return Err(CalcError::Domain("not a whole number")),
        Node::Neg(a) => (mask.wrapping_add(1).wrapping_sub(eval_int(a, w, signed)?)) & mask,
        Node::Not(a) => !eval_int(a, w, signed)? & mask,
        Node::Bin(op, a, b) => bin(
            *op,
            eval_int(a, w, signed)?,
            eval_int(b, w, signed)?,
            w,
            signed,
        )?,
    })
}

fn bin(op: Op, x: u128, y: u128, w: Width, signed: bool) -> Result<u128, CalcError> {
    let mask = w.mask();
    let bits = w.bits();
    Ok(match op {
        Op::Add => x.wrapping_add(y) & mask,
        Op::Sub => x.wrapping_sub(y) & mask,
        Op::Mul => x.wrapping_mul(y) & mask,
        Op::Div | Op::Rem if signed => {
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
        Op::Div | Op::Rem => {
            if y == 0 {
                return Err(CalcError::DivisionByZero);
            }
            (if op == Op::Div { x / y } else { x % y }) & mask
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

// --- float evaluator -------------------------------------------------

/// Evaluate `node` as ordinary `f64` arithmetic. Bitwise operators are not
/// defined here.
fn eval_float(node: &Node) -> Result<f64, CalcError> {
    Ok(match node {
        Node::Lit(LitVal::F(v)) => *v,
        Node::Lit(LitVal::I(v)) => *v as f64,
        Node::Neg(a) => -eval_float(a)?,
        Node::Not(_) => return Err(CalcError::Domain("bitwise ops need an integer mode")),
        Node::Bin(op, a, b) => {
            let x = eval_float(a)?;
            let y = eval_float(b)?;
            match op {
                Op::Add => x + y,
                Op::Sub => x - y,
                Op::Mul => x * y,
                Op::Div => x / y,
                Op::Rem => libm::fmod(x, y),
                _ => return Err(CalcError::Domain("bitwise ops need an integer mode")),
            }
        }
    })
}

/// Round `value` to the nearest number representable by a
/// `sign` / `exp` / `mant` float layout and return its raw bits.
fn float_encode(value: f64, sign: u32, exp: u32, mant: u32) -> u64 {
    // Native fast paths for the two standard layouts.
    if sign == 1 && exp == 8 && mant == 23 {
        return u64::from((value as f32).to_bits());
    }
    if sign == 1 && exp == 11 && mant == 52 {
        return value.to_bits();
    }

    let sign_shift = exp + mant;
    let exp_all = (1u64 << exp) - 1;
    let mant_mask = (1u64 << mant) - 1;
    let bias = (1i64 << (exp - 1)) - 1;

    let negative = value.is_sign_negative();
    let sign_bit = if sign == 1 && negative {
        1u64 << sign_shift
    } else {
        0
    };
    let mag = libm::fabs(value);
    let qnan = 1u64 << (mant - 1);

    if mag.is_nan() || (sign == 0 && negative && mag != 0.0) {
        return sign_bit | (exp_all << mant) | qnan;
    }
    if mag.is_infinite() {
        return sign_bit | (exp_all << mant);
    }
    if mag == 0.0 {
        return sign_bit;
    }

    // mag = frac * 2^e2, with 0.5 <= frac < 1  ->  significand in [1, 2), E = e2 - 1
    let (_frac, e2) = libm::frexp(mag);
    let unbiased = i64::from(e2) - 1;
    let mut biased = unbiased + bias;

    if biased >= exp_all as i64 {
        return sign_bit | (exp_all << mant); // overflow -> infinity
    }

    if biased <= 0 {
        // subnormal: value = m * 2^(1 - bias - mant)
        let step = 1 - bias - i64::from(mant);
        let scaled = libm::ldexp(mag, -(step as i32));
        let m = libm::roundeven(scaled) as u64;
        return if m == 0 {
            sign_bit
        } else if m > mant_mask {
            sign_bit | (1u64 << mant) // rounded up to the smallest normal
        } else {
            sign_bit | m
        };
    }

    // normal: mantissa = round((significand - 1) * 2^mant)
    let significand = libm::ldexp(mag, -(unbiased as i32)); // in [1, 2)
    let scaled = (significand - 1.0) * (1u64 << mant) as f64;
    let mut m = libm::roundeven(scaled) as u64;
    if m > mant_mask {
        m = 0;
        biased += 1;
        if biased >= exp_all as i64 {
            return sign_bit | (exp_all << mant);
        }
    }
    sign_bit | ((biased as u64) << mant) | (m & mant_mask)
}

/// Decode raw `bits` of a `sign` / `exp` / `mant` float layout to `f64`.
fn float_decode(bits: u64, sign: u32, exp: u32, mant: u32) -> f64 {
    if sign == 1 && exp == 8 && mant == 23 {
        return f64::from(f32::from_bits(bits as u32));
    }
    if sign == 1 && exp == 11 && mant == 52 {
        return f64::from_bits(bits);
    }

    let exp_all = (1u64 << exp) - 1;
    let mant_mask = (1u64 << mant) - 1;
    let bias = (1i64 << (exp - 1)) - 1;

    let m = bits & mant_mask;
    let e = (bits >> mant) & exp_all;
    let neg = sign == 1 && (bits >> (exp + mant)) & 1 == 1;

    let val = if e == exp_all {
        if m == 0 {
            f64::INFINITY
        } else {
            f64::NAN
        }
    } else if e == 0 {
        libm::ldexp(m as f64, (1 - bias - i64::from(mant)) as i32)
    } else {
        libm::ldexp(
            ((1u64 << mant) | m) as f64,
            (e as i64 - bias - i64::from(mant)) as i32,
        )
    };
    if neg {
        -val
    } else {
        val
    }
}

/// Format a float value for the value row, at a precision that suits a
/// `mant`-bit mantissa.
fn format_f64(v: f64, mant: u32) -> String {
    use core::fmt::Write;

    if v.is_nan() {
        return String::from("NaN");
    }
    if v.is_infinite() {
        return String::from(if v < 0.0 {
            "\u{2212}\u{221E}"
        } else {
            "\u{221E}"
        });
    }
    if v == 0.0 {
        return String::from(if v.is_sign_negative() {
            "\u{2212}0"
        } else {
            "0"
        });
    }

    // ~decimal digits that round-trip a (mant+1)-bit significand
    let digits = libm::ceil(f64::from(mant + 1) * core::f64::consts::LOG10_2);
    let sig = (digits as u32).clamp(3, 17);
    let r = round_to_sig(v, sig);
    let a = libm::fabs(r);

    let mut s = String::new();
    if a != 0.0 && !(1e-4..1e16).contains(&a) {
        let _ = write!(s, "{r:e}");
    } else {
        let _ = write!(s, "{r}");
    }
    // use the app's minus sign
    if let Some(stripped) = s.strip_prefix('-') {
        let mut out = String::from("\u{2212}");
        out.push_str(stripped);
        out
    } else {
        s
    }
}

/// Round `v` to `sig` significant decimal digits.
fn round_to_sig(v: f64, sig: u32) -> f64 {
    if v == 0.0 || !v.is_finite() {
        return v;
    }
    let d = i32::try_from(sig).unwrap_or(17) - 1 - libm::floor(libm::log10(libm::fabs(v))) as i32;
    let factor = libm::pow(10.0, f64::from(d));
    libm::round(v * factor) / factor
}

#[cfg(test)]
mod tests {
    use super::Width::{W16, W32, W64, W8};
    use super::*;

    fn ev(s: &str, w: Width) -> u64 {
        evaluate(s, w).unwrap_or_else(|e| panic!("`{s}` failed: {e:?}"))
    }

    fn evf(s: &str, fmt: NumFormat) -> u64 {
        evaluate_fmt(s, fmt).unwrap_or_else(|e| panic!("`{s}` failed: {e:?}"))
    }

    const F32: NumFormat = NumFormat::Float {
        sign: 1,
        exp: 8,
        mant: 23,
    };
    const F64: NumFormat = NumFormat::Float {
        sign: 1,
        exp: 11,
        mant: 52,
    };
    const F16: NumFormat = NumFormat::Float {
        sign: 1,
        exp: 5,
        mant: 10,
    };

    #[test]
    fn unsigned_division_differs_from_signed() {
        let u8f = NumFormat::Int {
            width: W8,
            signed: false,
        };
        let s8f = NumFormat::Int {
            width: W8,
            signed: true,
        };
        // 0xFF is 255 unsigned, -1 signed
        assert_eq!(evf("0xFF / 2", u8f), 127);
        assert_eq!(evf("0xFF / 2", s8f), 0);
        assert_eq!(evf("0xFF mod 4", u8f), 3);
        assert_eq!(evf("200 * 2", u8f), 144); // still wraps
        assert!(matches!(
            evaluate_fmt("1 / 0", u8f),
            Err(CalcError::DivisionByZero)
        ));
    }

    #[test]
    fn unsigned_and_signed_display() {
        let u8f = NumFormat::Int {
            width: W8,
            signed: false,
        };
        let s8f = NumFormat::Int {
            width: W8,
            signed: true,
        };
        assert_eq!(format_value(0xFF, u8f), "255");
        assert_eq!(format_value(0xFF, s8f), "-1");
        assert_eq!(format_value(0x80, s8f), "-128");
    }

    #[test]
    fn float_standard_layouts() {
        // 1.5 -> 0x3FC00000 (f32), 0x3FF8000000000000 (f64)
        assert_eq!(evf("1.5", F32), 0x3FC0_0000);
        assert_eq!(evf("1.5", F64), 0x3FF8_0000_0000_0000);
        assert_eq!(evf("0.5 + 0.25", F32), 0x3F40_0000); // 0.75
        assert_eq!(evf("1 / 4", F32), 0x3E80_0000); // 0.25
        assert_eq!(evf("-2.0", F32), 0xC000_0000);
        assert_eq!(evf("1 / 0", F32), 0x7F80_0000); // +inf, not an error
        assert_eq!(evf("0 / 0", F32) & 0x7FFF_FFFF, 0x7FC0_0000); // NaN
    }

    #[test]
    fn float_custom_half_precision() {
        // IEEE half: 1.0 -> 0x3C00, 2.0 -> 0x4000, -1.0 -> 0xBC00,
        // smallest normal -> 0x0400, largest subnormal -> 0x03FF
        assert_eq!(evf("1.0", F16), 0x3C00);
        assert_eq!(evf("2.0", F16), 0x4000);
        assert_eq!(evf("-1.0", F16), 0xBC00);
        assert_eq!(evf("0.5 * 0.5", F16), 0x3400); // 0.25
        assert_eq!(evf("65504", F16), 0x7BFF); // max finite half
        assert_eq!(evf("70000", F16), 0x7C00); // overflow -> inf
    }

    #[test]
    fn float_custom_generic_matches_native_f32() {
        for bits in [
            0x3FC0_0000u32,
            0x4049_0FDB,
            0xC280_0000,
            0x0000_0001,
            0x7F7F_FFFF,
        ] {
            let v = f64::from(f32::from_bits(bits));
            // generic encoder (not the fast path) reproduces the f32 bits
            assert_eq!(float_encode(v, 1, 8, 23), u64::from(bits));
        }
    }

    #[test]
    fn float_value_strings() {
        assert_eq!(format_value(evf("3.14159265", F64), F64), "3.14159265");
        assert_eq!(format_value(evf("1 / 3", F64), F64), "0.3333333333333333");
        assert_eq!(format_value(evf("1 / 3", F16), F16), "0.3333");
        assert_eq!(format_value(evf("1 / 0", F32), F32), "\u{221E}");
        assert_eq!(format_value(evf("-1 / 0", F32), F32), "\u{2212}\u{221E}");
        assert_eq!(format_value(0x7FC0_0000, F32), "NaN");
        assert_eq!(format_value(evf("2.0", F16), F16), "2");
    }

    #[test]
    fn float_inf_and_nan_literals_round_trip() {
        assert_eq!(evf("inf", F32), 0x7F80_0000);
        assert_eq!(evf("-inf", F32), 0xFF80_0000);
        assert_eq!(evf("inf - inf", F32) & 0x7FFF_FFFF, 0x7FC0_0000); // NaN
        assert_eq!(format_value(evf("nan + 1", F32), F32), "NaN");
        assert_eq!(evf("inf * 2", F16), 0x7C00);
    }

    #[test]
    fn float_mode_rejects_bitwise() {
        assert!(matches!(
            evaluate_fmt("1.5 & 2", F32),
            Err(CalcError::Domain(_))
        ));
        assert!(matches!(
            evaluate_fmt("~1.0", F32),
            Err(CalcError::Domain(_))
        ));
    }

    #[test]
    fn int_mode_rejects_fractional_literal() {
        assert!(matches!(
            evaluate_fmt(
                "1.5",
                NumFormat::Int {
                    width: W32,
                    signed: true
                }
            ),
            Err(CalcError::Domain(_))
        ));
    }

    #[test]
    fn wire_decoding_clamps() {
        assert_eq!(
            NumFormat::from_wire(0, 2, 0, 0),
            NumFormat::Int {
                width: W32,
                signed: true
            }
        );
        assert_eq!(
            NumFormat::from_wire(1, 0, 0, 0),
            NumFormat::Int {
                width: W8,
                signed: false
            }
        );
        // sign+exp+mant must fit in 64
        let f = NumFormat::from_wire(2, 1, 40, 40);
        if let NumFormat::Float { sign, exp, mant } = f {
            assert!(sign + exp + mant <= 64);
        } else {
            panic!("expected float");
        }
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
