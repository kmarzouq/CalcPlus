//! Tree-walking evaluator over [`Decimal`].
//!
//! Exact arithmetic (`+ - * /`, integer powers, `!`, `nCr`, `gcd`, …) stays in
//! `Decimal`. Transcendental functions (trig, logs, `exp`, fractional powers)
//! are computed in `f64` and converted back — the same practical trade-off a
//! TI-84 makes, and good to ~15 significant digits, well beyond the 12 the UI
//! shows. Every step is checked; user input never causes a panic.

use crate::ast::{BinOp, Expr};
use crate::error::CalcError;
use crate::parser;
use alloc::collections::BTreeMap;
use alloc::string::{String, ToString};
use core::str::FromStr;
use rust_decimal::prelude::{FromPrimitive, Signed, ToPrimitive};
use rust_decimal::{Decimal, MathematicalOps};

const HUNDRED: Decimal = Decimal::ONE_HUNDRED;
const PI_F64: f64 = core::f64::consts::PI;

/// How bare angle arguments/results are interpreted for trig functions.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum AngleMode {
    #[default]
    Radians,
    Degrees,
    /// 400 gradians in a full turn.
    Gradians,
}

/// Evaluation context: named variables + angle mode. Cheap to create; reused
/// across a graph plot's sample points.
#[derive(Debug, Default, Clone)]
pub struct Context {
    vars: BTreeMap<String, Decimal>,
    angle: AngleMode,
}

impl Context {
    pub fn new() -> Self {
        Self::default()
    }

    /// Bind a variable (e.g. `x` for graphing). Returns `self` for chaining.
    pub fn with_var(mut self, name: &str, value: Decimal) -> Self {
        self.vars.insert(name.to_string(), value);
        self
    }

    pub fn with_angle(mut self, mode: AngleMode) -> Self {
        self.angle = mode;
        self
    }

    pub fn set_var(&mut self, name: &str, value: Decimal) {
        self.vars.insert(name.to_string(), value);
    }

    pub fn set_angle(&mut self, mode: AngleMode) {
        self.angle = mode;
    }

    /// Parse and evaluate `input` in this context.
    pub fn evaluate(&self, input: &str) -> Result<Decimal, CalcError> {
        let expr = parser::parse(input)?;
        self.eval(&expr)
    }

    fn eval(&self, e: &Expr) -> Result<Decimal, CalcError> {
        match e {
            Expr::Num(d) => Ok(*d),
            Expr::Name(name) => self.resolve(name),
            Expr::Neg(inner) => Ok(-self.eval(inner)?),
            Expr::Percent(inner) => self
                .eval(inner)?
                .checked_div(HUNDRED)
                .ok_or(CalcError::Overflow),
            Expr::Factorial(inner) => factorial(self.eval(inner)?),
            Expr::Binary { op, lhs, rhs } => self.eval_binary(*op, lhs, rhs),
            Expr::Call { name, args } => self.eval_call(name, args),
        }
    }

    fn resolve(&self, name: &str) -> Result<Decimal, CalcError> {
        if let Some(v) = self.vars.get(name) {
            return Ok(*v);
        }
        constant(name).ok_or_else(|| CalcError::UnknownName(name.to_string()))
    }

    fn eval_binary(&self, op: BinOp, lhs: &Expr, rhs: &Expr) -> Result<Decimal, CalcError> {
        let l = self.eval(lhs)?;

        // Android-calculator percent semantics: in `a + b%` / `a - b%` the
        // right operand is read as "b percent *of a*".
        if matches!(op, BinOp::Add | BinOp::Sub) {
            if let Expr::Percent(pct) = rhs {
                let frac = self
                    .eval(pct)?
                    .checked_div(HUNDRED)
                    .ok_or(CalcError::Overflow)?;
                let delta = l.checked_mul(frac).ok_or(CalcError::Overflow)?;
                return match op {
                    BinOp::Add => l.checked_add(delta),
                    _ => l.checked_sub(delta),
                }
                .ok_or(CalcError::Overflow);
            }
        }

        let r = self.eval(rhs)?;
        match op {
            BinOp::Add => l.checked_add(r).ok_or(CalcError::Overflow),
            BinOp::Sub => l.checked_sub(r).ok_or(CalcError::Overflow),
            BinOp::Mul => l.checked_mul(r).ok_or(CalcError::Overflow),
            BinOp::Div => {
                if r.is_zero() {
                    Err(CalcError::DivisionByZero)
                } else {
                    l.checked_div(r).ok_or(CalcError::Overflow)
                }
            }
            BinOp::Pow => pow(l, r),
        }
    }

    fn eval_call(&self, name: &str, args: &[Expr]) -> Result<Decimal, CalcError> {
        // Evaluate all arguments once, up front.
        let a: Result<alloc::vec::Vec<Decimal>, CalcError> =
            args.iter().map(|e| self.eval(e)).collect();
        let a = a?;

        let want = |n: usize| -> Result<(), CalcError> {
            if a.len() == n {
                Ok(())
            } else {
                Err(CalcError::Arity {
                    name: name.to_string(),
                    expected: n,
                    got: a.len(),
                })
            }
        };
        match name {
            // --- exact -----------------------------------------------------
            "abs" => want(1).map(|()| a[0].abs()),
            "sign" => want(1).map(|()| a[0].signum()),
            "floor" => want(1).map(|()| a[0].floor()),
            "ceil" => want(1).map(|()| a[0].ceil()),
            "round" => want(1).map(|()| a[0].round()),
            "sqrt" => want(1).and_then(|_| {
                if a[0].is_sign_negative() {
                    Err(CalcError::Domain("sqrt of a negative number"))
                } else {
                    a[0].sqrt().ok_or(CalcError::Overflow)
                }
            }),
            "cbrt" => want(1).map(|()| cbrt(a[0])),
            "root" => want(2).and_then(|_| nth_root(a[0], a[1])),
            "pow" => want(2).and_then(|_| pow(a[0], a[1])),
            "mod" => want(2).and_then(|_| {
                if a[1].is_zero() {
                    Err(CalcError::DivisionByZero)
                } else {
                    a[0].checked_rem(a[1]).ok_or(CalcError::Overflow)
                }
            }),
            "gcd" => want(2).and_then(|_| int_binop(a[0], a[1], gcd)),
            "lcm" => want(2).and_then(|_| int_binop(a[0], a[1], lcm)),
            "ncr" => want(2).and_then(|_| n_choose_r(a[0], a[1], false)),
            "npr" => want(2).and_then(|_| n_choose_r(a[0], a[1], true)),
            "min" | "max" => {
                if a.is_empty() {
                    return Err(CalcError::Arity {
                        name: name.to_string(),
                        expected: 1,
                        got: 0,
                    });
                }
                Ok(a.iter()
                    .copied()
                    .reduce(|acc, x| {
                        if name == "min" {
                            acc.min(x)
                        } else {
                            acc.max(x)
                        }
                    })
                    .unwrap_or(Decimal::ZERO))
            }

            // --- transcendental (via f64 / libm) ------------------------
            "sin" => want(1).and_then(|_| self.trig(a[0], libm::sin)),
            "cos" => want(1).and_then(|_| self.trig(a[0], libm::cos)),
            "tan" => want(1).and_then(|_| self.trig(a[0], libm::tan)),
            "asin" => want(1).and_then(|_| {
                bounded(a[0], "asin is defined on [-1, 1]")?;
                self.arc(a[0], libm::asin)
            }),
            "acos" => want(1).and_then(|_| {
                bounded(a[0], "acos is defined on [-1, 1]")?;
                self.arc(a[0], libm::acos)
            }),
            "atan" => want(1).and_then(|_| self.arc(a[0], libm::atan)),
            "atan2" => want(2).and_then(|_| {
                let (y, x) = (to_f64(a[0])?, to_f64(a[1])?);
                self.angle_from_radians(libm::atan2(y, x))
            }),
            "sinh" => want(1).and_then(|_| f1(a[0], libm::sinh)),
            "cosh" => want(1).and_then(|_| f1(a[0], libm::cosh)),
            "tanh" => want(1).and_then(|_| f1(a[0], libm::tanh)),
            "asinh" => want(1).and_then(|_| f1(a[0], libm::asinh)),
            "acosh" => want(1).and_then(|_| {
                if a[0] < Decimal::ONE {
                    Err(CalcError::Domain("acosh is defined on [1, ∞)"))
                } else {
                    f1(a[0], libm::acosh)
                }
            }),
            "atanh" => want(1).and_then(|_| {
                if a[0] <= -Decimal::ONE || a[0] >= Decimal::ONE {
                    Err(CalcError::Domain("atanh is defined on (-1, 1)"))
                } else {
                    f1(a[0], libm::atanh)
                }
            }),
            "exp" => want(1).and_then(|_| f1(a[0], libm::exp)),
            "ln" => want(1).and_then(|_| {
                positive(a[0], "ln of a non-positive number")?;
                f1(a[0], libm::log)
            }),
            "log2" => want(1).and_then(|_| {
                positive(a[0], "log2 of a non-positive number")?;
                f1(a[0], libm::log2)
            }),
            "log" | "log10" => {
                if a.len() == 2 {
                    positive(a[0], "log of a non-positive base")?;
                    positive(a[1], "log of a non-positive number")?;
                    let (b, x) = (to_f64(a[0])?, to_f64(a[1])?);
                    finish(libm::log(x) / libm::log(b))
                } else {
                    want(1)?;
                    positive(a[0], "log of a non-positive number")?;
                    f1(a[0], libm::log10)
                }
            }
            "hypot" => want(2).and_then(|_| {
                let (x, y) = (to_f64(a[0])?, to_f64(a[1])?);
                finish(libm::hypot(x, y))
            }),

            _ => Err(CalcError::UnknownName(name.to_string())),
        }
    }

    /// Trig: interpret the argument per the angle mode, compute in radians.
    fn trig(&self, x: Decimal, f: fn(f64) -> f64) -> Result<Decimal, CalcError> {
        let x = to_f64(x)?;
        let radians = match self.angle {
            AngleMode::Radians => x,
            AngleMode::Degrees => x * PI_F64 / 180.0,
            AngleMode::Gradians => x * PI_F64 / 200.0,
        };
        finish(f(radians))
    }

    /// Inverse trig: `f` returns radians; convert the result to the angle mode.
    fn arc(&self, x: Decimal, f: fn(f64) -> f64) -> Result<Decimal, CalcError> {
        self.angle_from_radians(f(to_f64(x)?))
    }

    fn angle_from_radians(&self, radians: f64) -> Result<Decimal, CalcError> {
        let out = match self.angle {
            AngleMode::Radians => radians,
            AngleMode::Degrees => radians * 180.0 / PI_F64,
            AngleMode::Gradians => radians * 200.0 / PI_F64,
        };
        finish(out)
    }
}

// --- f64 bridge -----------------------------------------------------------

fn to_f64(x: Decimal) -> Result<f64, CalcError> {
    x.to_f64().ok_or(CalcError::Overflow)
}

fn finite(r: f64) -> Result<f64, CalcError> {
    if r.is_nan() {
        Err(CalcError::Domain("result is not a real number"))
    } else if r.is_infinite() {
        Err(CalcError::Overflow)
    } else {
        Ok(r)
    }
}

/// Finish an `f64` computation: reject NaN/∞, convert back to `Decimal`.
fn finish(r: f64) -> Result<Decimal, CalcError> {
    let r = finite(r)?;
    Decimal::from_f64(r).ok_or(CalcError::Overflow)
}

/// One-argument `f64` function with no angle handling.
fn f1(x: Decimal, f: fn(f64) -> f64) -> Result<Decimal, CalcError> {
    finish(f(to_f64(x)?))
}

// --- domain guards ------------------------------------------------------

fn positive(x: Decimal, msg: &'static str) -> Result<(), CalcError> {
    if x <= Decimal::ZERO {
        Err(CalcError::Domain(msg))
    } else {
        Ok(())
    }
}

fn bounded(x: Decimal, msg: &'static str) -> Result<(), CalcError> {
    if x < -Decimal::ONE || x > Decimal::ONE {
        Err(CalcError::Domain(msg))
    } else {
        Ok(())
    }
}

// --- exact helpers -----------------------------------------------------

/// `base ^ exp`. Integer exponents use exact `checked_powi`; fractional ones
/// fall through to the `f64` path. Negative base with a fractional exponent is
/// a domain error.
fn pow(base: Decimal, exp: Decimal) -> Result<Decimal, CalcError> {
    if exp.fract().is_zero() {
        if let Some(i) = exp.to_i64() {
            return base.checked_powi(i).ok_or(CalcError::Overflow);
        }
    }
    if base.is_sign_negative() {
        return Err(CalcError::Domain("fractional power of a negative number"));
    }
    finish(libm::pow(to_f64(base)?, to_f64(exp)?))
}

/// `x` to the `1/n` power. Handles negative `x` when `n` is an odd integer.
fn nth_root(n: Decimal, x: Decimal) -> Result<Decimal, CalcError> {
    if n.is_zero() {
        return Err(CalcError::Domain("0th root"));
    }
    if x.is_sign_negative() {
        let is_odd_int = n.fract().is_zero() && n.to_i64().is_some_and(|i| i % 2 != 0);
        if !is_odd_int {
            return Err(CalcError::Domain(
                "even/fractional root of a negative number",
            ));
        }
        return Ok(-finish(libm::pow(to_f64(-x)?, 1.0 / to_f64(n)?))?);
    }
    finish(libm::pow(to_f64(x)?, 1.0 / to_f64(n)?))
}

fn cbrt(x: Decimal) -> Decimal {
    if x.is_zero() {
        return Decimal::ZERO;
    }
    let neg = x.is_sign_negative();
    let mag = x.abs();
    // Newton's method; Decimal has no native cbrt.
    let mut g = mag
        .checked_powd(Decimal::ONE / Decimal::from(3))
        .unwrap_or(mag);
    for _ in 0..40 {
        let g2 = g * g;
        if g2.is_zero() {
            break;
        }
        let next = (Decimal::from(2) * g + mag / g2) / Decimal::from(3);
        if (next - g).abs() < Decimal::new(1, 20) {
            g = next;
            break;
        }
        g = next;
    }
    if neg {
        -g
    } else {
        g
    }
}

fn factorial(x: Decimal) -> Result<Decimal, CalcError> {
    let n = non_negative_int(x, "factorial needs a non-negative integer")?;
    let mut acc = Decimal::ONE;
    for k in 2..=n {
        acc = acc
            .checked_mul(Decimal::from(k))
            .ok_or(CalcError::Overflow)?;
    }
    Ok(acc)
}

/// `nPr` (ordered) or `nCr` (unordered), exact.
fn n_choose_r(n: Decimal, r: Decimal, ordered: bool) -> Result<Decimal, CalcError> {
    let n = non_negative_int(n, "nCr/nPr need non-negative integers")?;
    let r = non_negative_int(r, "nCr/nPr need non-negative integers")?;
    if r > n {
        return Ok(Decimal::ZERO);
    }
    // product of the top r terms: n * (n-1) * ... * (n-r+1)
    let mut acc = Decimal::ONE;
    for k in 0..r {
        acc = acc
            .checked_mul(Decimal::from(n - k))
            .ok_or(CalcError::Overflow)?;
    }
    if ordered {
        return Ok(acc);
    }
    for k in 2..=r {
        acc = acc
            .checked_div(Decimal::from(k))
            .ok_or(CalcError::Overflow)?;
    }
    Ok(acc.round())
}

fn int_binop(
    a: Decimal,
    b: Decimal,
    f: fn(u128, u128) -> Option<u128>,
) -> Result<Decimal, CalcError> {
    let x = a.abs();
    let y = b.abs();
    if !x.fract().is_zero() || !y.fract().is_zero() {
        return Err(CalcError::Domain("gcd/lcm need integers"));
    }
    let (xi, yi) = (
        x.to_u128().ok_or(CalcError::Overflow)?,
        y.to_u128().ok_or(CalcError::Overflow)?,
    );
    let r = f(xi, yi).ok_or(CalcError::Overflow)?;
    Decimal::from_u128(r).ok_or(CalcError::Overflow)
}

fn gcd(mut a: u128, mut b: u128) -> Option<u128> {
    while b != 0 {
        (a, b) = (b, a % b);
    }
    Some(a)
}

fn lcm(a: u128, b: u128) -> Option<u128> {
    if a == 0 || b == 0 {
        return Some(0);
    }
    let g = gcd(a, b)?;
    (a / g).checked_mul(b)
}

fn non_negative_int(x: Decimal, msg: &'static str) -> Result<u64, CalcError> {
    if x.is_sign_negative() || !x.fract().is_zero() {
        return Err(CalcError::Domain(msg));
    }
    x.to_u64().ok_or(CalcError::Overflow)
}

/// Mathematical constants. Values carry more digits than `Decimal` keeps so the
/// stored value is correctly rounded.
fn constant(name: &str) -> Option<Decimal> {
    let text = match name {
        "pi" => "3.1415926535897932384626433833",
        "e" => "2.7182818284590452353602874714",
        "tau" => "6.2831853071795864769252867666",
        "phi" => "1.6180339887498948482045868344",
        _ => return None,
    };
    Decimal::from_str(text).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;

    fn ev(s: &str) -> Decimal {
        Context::new().evaluate(s).unwrap()
    }

    fn ev_deg(s: &str) -> Decimal {
        Context::new()
            .with_angle(AngleMode::Degrees)
            .evaluate(s)
            .unwrap()
    }

    #[test]
    fn variables() {
        let ctx = Context::new().with_var("x", dec!(5));
        assert_eq!(ctx.evaluate("x ^ 2 + 1").unwrap(), dec!(26));
    }

    #[test]
    fn modulo_and_pow() {
        assert_eq!(ev("mod(17, 5)"), dec!(2));
        assert_eq!(ev("2 ^ 10"), dec!(1024));
        assert_eq!(ev("2 ^ -3"), dec!(0.125));
    }

    #[test]
    fn roots() {
        assert_eq!(ev("cbrt(27)").round_dp(6), dec!(3));
        assert_eq!(ev("cbrt(-8)").round_dp(6), dec!(-2));
        assert_eq!(ev("sqrt(2)").round_dp(6), dec!(1.414214));
        assert_eq!(ev("root(3, 27)").round_dp(6), dec!(3));
        assert_eq!(ev("root(3, -8)").round_dp(6), dec!(-2));
        assert!(matches!(
            Context::new().evaluate("root(2, -4)"),
            Err(CalcError::Domain(_))
        ));
    }

    #[test]
    fn trig_radians_and_degrees() {
        assert_eq!(ev("sin(0)"), dec!(0));
        assert_eq!(ev("cos(0)"), dec!(1));
        assert_eq!(ev_deg("sin(90)").round_dp(10), dec!(1));
        assert_eq!(ev_deg("cos(180)").round_dp(10), dec!(-1));
        assert_eq!(ev_deg("tan(45)").round_dp(10), dec!(1));
        assert_eq!(ev_deg("asin(1)").round_dp(6), dec!(90));
        assert_eq!(ev_deg("atan2(1, 1)").round_dp(6), dec!(45));
    }

    #[test]
    fn logs_and_exp() {
        assert_eq!(ev("ln(e)").round_dp(10), dec!(1));
        assert_eq!(ev("log(1000)").round_dp(10), dec!(3));
        assert_eq!(ev("log2(1024)").round_dp(10), dec!(10));
        assert_eq!(ev("log(2, 8)").round_dp(10), dec!(3));
        assert_eq!(ev("exp(0)"), dec!(1));
        assert!(matches!(
            Context::new().evaluate("ln(-1)"),
            Err(CalcError::Domain(_))
        ));
    }

    #[test]
    fn combinatorics_and_integers() {
        assert_eq!(ev("ncr(5, 2)"), dec!(10));
        assert_eq!(ev("npr(5, 2)"), dec!(20));
        assert_eq!(ev("ncr(52, 5)"), dec!(2598960));
        assert_eq!(ev("gcd(48, 18)"), dec!(6));
        assert_eq!(ev("lcm(4, 6)"), dec!(12));
    }

    #[test]
    fn hyperbolic() {
        assert_eq!(ev("sinh(0)"), dec!(0));
        assert_eq!(ev("cosh(0)"), dec!(1));
        assert_eq!(ev("tanh(0)"), dec!(0));
        assert!(matches!(
            Context::new().evaluate("acosh(0)"),
            Err(CalcError::Domain(_))
        ));
    }

    #[test]
    fn domain_and_zero() {
        assert_eq!(
            Context::new().evaluate("5 / 0"),
            Err(CalcError::DivisionByZero)
        );
        assert_eq!(
            Context::new().evaluate("mod(5, 0)"),
            Err(CalcError::DivisionByZero)
        );
        assert!(matches!(
            Context::new().evaluate("asin(2)"),
            Err(CalcError::Domain(_))
        ));
    }
}
