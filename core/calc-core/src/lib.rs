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
    fn degrees_mode_via_entry_point() {
        let opts = FormatOptions::default();
        assert_eq!(
            evaluate_to_string("sin(30)", &opts, AngleMode::Degrees).unwrap(),
            "0.5",
        );
    }
}
