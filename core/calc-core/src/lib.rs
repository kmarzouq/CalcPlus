// SPDX-License-Identifier: GPL-2.0-only
//! Expression engine for CalculatorApp.
//!
//! Pipeline: `&str` -> [`lexer`] -> [`parser`] (Pratt) -> [`ast::Expr`] ->
//! [`eval`] -> [`rust_decimal::Decimal`].
//!
//! The crate is `no_std` (only `alloc`) so the shipped `.so` stays small.
//! `std` is linked only for the test binary.
#![cfg_attr(not(test), no_std)]
#![forbid(unsafe_code)]
// The no-panic lints (Cargo.toml) guard shipped code; tests may unwrap freely.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;
use rust_decimal::prelude::{FromPrimitive, ToPrimitive};

pub mod ast;
pub mod error;
pub mod eval;
pub mod format;
pub mod lexer;
pub mod parser;

pub use error::CalcError;
pub use eval::{AngleMode, Context};
pub use format::FormatOptions;
pub use rust_decimal::Decimal;

/// Evaluate `input` against a fresh radian-mode context.
pub fn evaluate(input: &str) -> Result<Decimal, CalcError> {
    Context::new().evaluate(input)
}

/// Evaluate `input` and render the result with `opts` (grouping, precision,
/// scientific-notation thresholds), interpreting angles per `angle`.
///
/// This is the entry point the FFI layer calls: one string in, one string out.
pub fn evaluate_to_string(
    input: &str,
    opts: &FormatOptions,
    angle: AngleMode,
) -> Result<String, CalcError> {
    Context::new()
        .with_angle(angle)
        .evaluate(input)
        .map(|value| format::render(value, opts))
}

/// Sample `input` — a function of the variable `x` — at `n` evenly spaced
/// points across `[x_min, x_max]`.
///
/// Points where the function is undefined, complex, or non-finite come back as
/// [`f64::NAN`] (the graph draws a gap there). The only hard error is a
/// malformed expression.
pub fn sample(
    input: &str,
    x_min: f64,
    x_max: f64,
    n: usize,
    angle: AngleMode,
) -> Result<Vec<f64>, CalcError> {
    let expr = parser::parse(input)?;
    let mut ctx = Context::new().with_angle(angle);
    let mut ys = Vec::with_capacity(n);
    let span = x_max - x_min;
    for i in 0..n {
        let t = if n > 1 {
            i as f64 / (n - 1) as f64
        } else {
            0.0
        };
        let x = x_min + span * t;
        ctx.set_var("x", Decimal::from_f64(x).unwrap_or(Decimal::ZERO));
        let y = ctx
            .eval_ast(&expr)
            .ok()
            .and_then(|d| d.to_f64())
            .filter(|v| v.is_finite())
            .unwrap_or(f64::NAN);
        ys.push(y);
    }
    Ok(ys)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;

    fn ev(s: &str) -> Decimal {
        evaluate(s).unwrap_or_else(|e| panic!("`{s}` failed: {e:?}"))
    }

    #[test]
    fn arithmetic_and_precedence() {
        assert_eq!(ev("1 + 2 * 3"), dec!(7));
        assert_eq!(ev("(1 + 2) * 3"), dec!(9));
        assert_eq!(ev("2 - 3 - 4"), dec!(-5)); // left-associative
        assert_eq!(ev("2 ^ 3 ^ 2"), dec!(512)); // right-associative
        assert_eq!(ev("-2 ^ 2"), dec!(-4)); // unary minus binds looser than ^
        assert_eq!(ev("10 / 4"), dec!(2.5));
    }

    #[test]
    fn decimal_is_exact_where_float_is_not() {
        assert_eq!(ev("0.1 + 0.2"), dec!(0.3));
    }

    #[test]
    fn percent_matches_android_calculator() {
        assert_eq!(ev("100 + 10%"), dec!(110));
        assert_eq!(ev("100 - 10%"), dec!(90));
        assert_eq!(ev("200 * 10%"), dec!(20));
        assert_eq!(ev("50%"), dec!(0.5));
    }

    #[test]
    fn constants_and_functions() {
        assert_eq!(ev("sqrt(9)"), dec!(3));
        assert_eq!(ev("abs(-7)"), dec!(7));
        assert_eq!(ev("3!"), dec!(6));
        assert!((ev("pi") - dec!(3.14159265358979)).abs() < dec!(0.0000001));
    }

    #[test]
    fn errors() {
        assert!(matches!(evaluate("1 / 0"), Err(CalcError::DivisionByZero)));
        assert!(matches!(evaluate("sqrt(-1)"), Err(CalcError::Domain(_))));
        assert!(matches!(evaluate("1 +"), Err(CalcError::UnexpectedEnd)));
        assert!(matches!(evaluate("1 2"), Err(CalcError::Syntax { .. })));
        assert!(matches!(evaluate("foo(2)"), Err(CalcError::UnknownName(_))));
    }

    #[test]
    fn formatting() {
        let opts = FormatOptions::default();
        let r = AngleMode::Radians;
        assert_eq!(
            evaluate_to_string("1000000 + 234", &opts, r).unwrap(),
            "1,000,234"
        );
        assert_eq!(
            evaluate_to_string("1 / 3", &opts, r).unwrap(),
            "0.333333333333"
        );
        assert_eq!(evaluate_to_string("2 / 4", &opts, r).unwrap(), "0.5");
    }

    #[test]
    fn sampling_a_function() {
        // y = x^2 on [-2, 2], 5 points -> 4, 1, 0, 1, 4
        let ys = sample("x^2", -2.0, 2.0, 5, AngleMode::Radians).unwrap();
        assert_eq!(ys, vec![4.0, 1.0, 0.0, 1.0, 4.0]);

        // implicit multiplication works: 2x
        let ys = sample("2x", 0.0, 4.0, 5, AngleMode::Radians).unwrap();
        assert_eq!(ys, vec![0.0, 2.0, 4.0, 6.0, 8.0]);

        // undefined points -> NaN, not an error
        let ys = sample("1/x", -1.0, 1.0, 3, AngleMode::Radians).unwrap();
        assert_eq!(ys[0], -1.0);
        assert!(ys[1].is_nan()); // 1/0
        assert_eq!(ys[2], 1.0);

        // a bad expression is the only hard error
        assert!(sample("x +", 0.0, 1.0, 2, AngleMode::Radians).is_err());
    }

    #[test]
    fn degrees_mode_via_entry_point() {
        let opts = FormatOptions::default();
        assert_eq!(
            evaluate_to_string("sin(30)", &opts, AngleMode::Degrees).unwrap(),
            "0.5",
        );
    }
}
