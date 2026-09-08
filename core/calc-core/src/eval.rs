//! Tree-walking evaluator over [`Decimal`]. Every arithmetic step is checked;
//! a failure is a [`CalcError`], never a panic.

use crate::ast::{BinOp, Expr};
use crate::error::CalcError;
use crate::parser;
use alloc::collections::BTreeMap;
use alloc::string::{String, ToString};
use core::str::FromStr;
use rust_decimal::prelude::{Signed, ToPrimitive};
use rust_decimal::{Decimal, MathematicalOps};

const HUNDRED: Decimal = Decimal::ONE_HUNDRED;

/// Evaluation context: named variables plus (later) user settings such as
/// angle mode. Cheap to create; reused across a graph plot's sample points.
#[derive(Debug, Default, Clone)]
pub struct Context {
    vars: BTreeMap<String, Decimal>,
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

    pub fn set_var(&mut self, name: &str, value: Decimal) {
        self.vars.insert(name.to_string(), value);
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
        let mut v = [Decimal::ZERO; 2];
        let argc = args.len();

        let arity = |want: usize| -> Result<(), CalcError> {
            if argc == want {
                Ok(())
            } else {
                Err(CalcError::Arity {
                    name: name.to_string(),
                    expected: want,
                    got: argc,
                })
            }
        };

        // Evaluate up to two positional args eagerly (all builtins are 1- or 2-ary
        // except min/max which are handled separately).
        for (slot, a) in v.iter_mut().zip(args.iter()) {
            *slot = self.eval(a)?;
        }

        match name {
            "sqrt" => {
                arity(1)?;
                if v[0].is_sign_negative() {
                    return Err(CalcError::Domain("sqrt of a negative number"));
                }
                v[0].sqrt().ok_or(CalcError::Overflow)
            }
            "cbrt" => {
                arity(1)?;
                Ok(cbrt(v[0]))
            }
            "abs" => {
                arity(1)?;
                Ok(v[0].abs())
            }
            "sign" => {
                arity(1)?;
                Ok(v[0].signum())
            }
            "ln" => {
                arity(1)?;
                domain_positive(v[0], "ln of a non-positive number")?;
                Ok(v[0].ln())
            }
            "log" | "log10" => {
                arity(1)?;
                domain_positive(v[0], "log of a non-positive number")?;
                Ok(v[0].log10())
            }
            "exp" => {
                arity(1)?;
                Ok(v[0].exp())
            }
            "sin" => {
                arity(1)?;
                Ok(v[0].sin())
            }
            "cos" => {
                arity(1)?;
                Ok(v[0].cos())
            }
            "tan" => {
                arity(1)?;
                Ok(v[0].tan())
            }
            "floor" => {
                arity(1)?;
                Ok(v[0].floor())
            }
            "ceil" => {
                arity(1)?;
                Ok(v[0].ceil())
            }
            "round" => {
                arity(1)?;
                Ok(v[0].round())
            }
            "mod" => {
                arity(2)?;
                if v[1].is_zero() {
                    Err(CalcError::DivisionByZero)
                } else {
                    v[0].checked_rem(v[1]).ok_or(CalcError::Overflow)
                }
            }
            "pow" => {
                arity(2)?;
                pow(v[0], v[1])
            }
            "min" | "max" => {
                if args.is_empty() {
                    return Err(CalcError::Arity {
                        name: name.to_string(),
                        expected: 1,
                        got: 0,
                    });
                }
                let mut acc = self.eval(&args[0])?;
                for a in &args[1..] {
                    let x = self.eval(a)?;
                    acc = if name == "min" {
                        acc.min(x)
                    } else {
                        acc.max(x)
                    };
                }
                Ok(acc)
            }
            _ => Err(CalcError::UnknownName(name.to_string())),
        }
    }
}

fn domain_positive(x: Decimal, msg: &'static str) -> Result<(), CalcError> {
    if x <= Decimal::ZERO {
        Err(CalcError::Domain(msg))
    } else {
        Ok(())
    }
}

/// `base ^ exp`. Integer exponents go through exact `checked_powi`; anything
/// else through `checked_powd`. Negative base with a fractional exponent is a
/// domain error (no real result).
fn pow(base: Decimal, exp: Decimal) -> Result<Decimal, CalcError> {
    if exp.fract().is_zero() {
        if let Some(i) = exp.to_i64() {
            return base.checked_powi(i).ok_or(CalcError::Overflow);
        }
    }
    if base.is_sign_negative() {
        return Err(CalcError::Domain("fractional power of a negative number"));
    }
    base.checked_powd(exp).ok_or(CalcError::Overflow)
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
    if x.is_sign_negative() || !x.fract().is_zero() {
        return Err(CalcError::Domain("factorial needs a non-negative integer"));
    }
    let n = x.to_u64().ok_or(CalcError::Overflow)?;
    let mut acc = Decimal::ONE;
    for k in 2..=n {
        acc = acc
            .checked_mul(Decimal::from(k))
            .ok_or(CalcError::Overflow)?;
    }
    Ok(acc)
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
    fn cbrt_and_sqrt() {
        assert_eq!(ev("cbrt(27)").round_dp(6), dec!(3));
        assert_eq!(ev("cbrt(-8)").round_dp(6), dec!(-2));
        assert_eq!(ev("sqrt(2)").round_dp(6), dec!(1.414214));
    }

    #[test]
    fn min_max_variadic() {
        assert_eq!(ev("max(3, 9, 1, 7)"), dec!(9));
        assert_eq!(ev("min(3, 9, 1, 7)"), dec!(1));
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
            Context::new().evaluate("ln(0)"),
            Err(CalcError::Domain(_))
        ));
    }
}
