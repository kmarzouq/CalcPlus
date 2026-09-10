// SPDX-License-Identifier: GPL-2.0-only
//! Numeric analysis of a `y = f(x)` expression — the maths behind the grapher's
//! CALC menu (roots, extrema, intersections, dy/dx, ∫f(x)dx) and the
//! calculator's `integral(f, a, b)` function.
//!
//! Everything works in `f64`: `x` is swept through a reused [`Context`] and each
//! sample goes through the ordinary [`eval`](crate::eval) path, so these accept
//! exactly the syntax the calculator does. Points where `f` is undefined come
//! back as `NaN` and simply break a bracket, mirroring the grapher's gaps.

use crate::ast::{BinOp, Expr};
use crate::error::CalcError;
use crate::eval::{AngleMode, Context};
use crate::parser;
use alloc::boxed::Box;
use rust_decimal::prelude::{FromPrimitive, ToPrimitive};
use rust_decimal::Decimal;

/// `f(x)` for the current `expr`, or `NaN` where it is undefined / non-finite.
fn f_at(ctx: &mut Context, expr: &Expr, x: f64) -> f64 {
    ctx.set_var("x", Decimal::from_f64(x).unwrap_or(Decimal::ZERO));
    ctx.eval_ast(expr)
        .ok()
        .and_then(|d| d.to_f64())
        .filter(|v| v.is_finite())
        .unwrap_or(f64::NAN)
}

fn ctx_for(angle: AngleMode) -> Context {
    Context::new().with_angle(angle)
}

// --- ∫ f(x) dx --------------------------------------------------------------

/// Composite Simpson's rule for ∫`expr` from `a` to `b` (swapped bounds negate).
/// Returns `NaN` if the integrand is undefined anywhere on the interval.
pub fn integrate_expr(ctx: &mut Context, expr: &Expr, a: f64, b: f64) -> f64 {
    if !a.is_finite() || !b.is_finite() {
        return f64::NAN;
    }
    if a == b {
        return 0.0;
    }
    let (lo, hi, sign) = if a < b { (a, b, 1.0) } else { (b, a, -1.0) };
    let n: usize = 4096; // even
    let h = (hi - lo) / n as f64;
    let mut sum = f_at(ctx, expr, lo) + f_at(ctx, expr, hi);
    for i in 1..n {
        let y = f_at(ctx, expr, lo + i as f64 * h);
        if !y.is_finite() {
            return f64::NAN;
        }
        sum += if i % 2 == 1 { 4.0 } else { 2.0 } * y;
    }
    if !sum.is_finite() {
        return f64::NAN;
    }
    sign * sum * h / 3.0
}

/// Parse `input` and integrate it from `a` to `b` over `x`.
pub fn integrate(input: &str, a: f64, b: f64, angle: AngleMode) -> Result<f64, CalcError> {
    let expr = parser::parse(input)?;
    Ok(integrate_expr(&mut ctx_for(angle), &expr, a, b))
}

// --- dy/dx ----------------------------------------------------------------

/// Central-difference derivative of `input` at `x`.
pub fn derivative(input: &str, x: f64, angle: AngleMode) -> Result<f64, CalcError> {
    let expr = parser::parse(input)?;
    let mut ctx = ctx_for(angle);
    let h = 1e-6 * x.abs().max(1.0);
    Ok((f_at(&mut ctx, &expr, x + h) - f_at(&mut ctx, &expr, x - h)) / (2.0 * h))
}

/// `f(x)` at a single point (the CALC "value" tool).
pub fn value(input: &str, x: f64, angle: AngleMode) -> Result<f64, CalcError> {
    let expr = parser::parse(input)?;
    Ok(f_at(&mut ctx_for(angle), &expr, x))
}

// --- roots / intersections ----------------------------------------------

/// Bisect for a sign-change root in `[a, b]`; `NaN` if `f(a)`, `f(b)` share a
/// sign or aren't both finite.
fn bisect(ctx: &mut Context, expr: &Expr, mut a: f64, mut b: f64) -> f64 {
    let mut fa = f_at(ctx, expr, a);
    let fb = f_at(ctx, expr, b);
    if !fa.is_finite() || !fb.is_finite() || fa * fb > 0.0 {
        return f64::NAN;
    }
    for _ in 0..100 {
        let m = 0.5 * (a + b);
        let fm = f_at(ctx, expr, m);
        if !fm.is_finite() {
            return f64::NAN;
        }
        if fm == 0.0 || (b - a).abs() <= 1e-13 * m.abs().max(1.0) {
            return m;
        }
        if fa * fm < 0.0 {
            b = m;
        } else {
            a = m;
            fa = fm;
        }
    }
    0.5 * (a + b)
}

/// Scan `[a, b]` (200 steps) for the first root of `expr` and refine it.
fn scan_root(ctx: &mut Context, expr: &Expr, a: f64, b: f64) -> f64 {
    if !a.is_finite() || !b.is_finite() || a == b {
        return f64::NAN;
    }
    let steps = 200;
    let h = (b - a) / steps as f64;
    let mut px = a;
    let mut py = f_at(ctx, expr, a);
    if py == 0.0 {
        return a;
    }
    for i in 1..=steps {
        let x = a + i as f64 * h;
        let y = f_at(ctx, expr, x);
        if y == 0.0 {
            return x;
        }
        if py.is_finite() && y.is_finite() && py * y < 0.0 {
            return bisect(ctx, expr, px, x);
        }
        px = x;
        py = y;
    }
    f64::NAN
}

/// The first root of `input` in `[a, b]` (CALC "zero"), or `NaN`.
pub fn root(input: &str, a: f64, b: f64, angle: AngleMode) -> Result<f64, CalcError> {
    let expr = parser::parse(input)?;
    Ok(scan_root(&mut ctx_for(angle), &expr, a, b))
}

/// An intersection of `f1` and `f2` in `[a, b]` → `(x, y)`, or `(NaN, NaN)`.
pub fn intersect(
    f1: &str,
    f2: &str,
    a: f64,
    b: f64,
    angle: AngleMode,
) -> Result<(f64, f64), CalcError> {
    let e1 = parser::parse(f1)?;
    let e2 = parser::parse(f2)?;
    let diff = Expr::Binary {
        op: BinOp::Sub,
        lhs: Box::new(e1),
        rhs: Box::new(e2.clone()),
    };
    let mut ctx = ctx_for(angle);
    let x = scan_root(&mut ctx, &diff, a, b);
    if x.is_nan() {
        return Ok((f64::NAN, f64::NAN));
    }
    Ok((x, f_at(&mut ctx, &e2, x)))
}

// --- extrema ------------------------------------------------------------

/// Golden-section search for a local minimum (`want_max` negates) of `input`
/// in `[a, b]` → `(x, f(x))`. Assumes one extremum in the bracket, like a
/// TI-84's minimum/maximum.
pub fn extremum(
    input: &str,
    a: f64,
    b: f64,
    want_max: bool,
    angle: AngleMode,
) -> Result<(f64, f64), CalcError> {
    let expr = parser::parse(input)?;
    let mut ctx = ctx_for(angle);
    let (mut lo, mut hi) = (a.min(b), a.max(b));
    let inv_phi = (libm::sqrt(5.0) - 1.0) / 2.0; // 1/φ ≈ 0.618
    let sgn = if want_max { -1.0 } else { 1.0 };
    let mut c = hi - inv_phi * (hi - lo);
    let mut d = lo + inv_phi * (hi - lo);
    let mut fc = sgn * f_at(&mut ctx, &expr, c);
    let mut fd = sgn * f_at(&mut ctx, &expr, d);
    for _ in 0..120 {
        if (hi - lo).abs() <= 1e-11 * hi.abs().max(1.0) {
            break;
        }
        if fc < fd {
            hi = d;
            d = c;
            fd = fc;
            c = hi - inv_phi * (hi - lo);
            fc = sgn * f_at(&mut ctx, &expr, c);
        } else {
            lo = c;
            c = d;
            fc = fd;
            d = lo + inv_phi * (hi - lo);
            fd = sgn * f_at(&mut ctx, &expr, d);
        }
    }
    let x = 0.5 * (lo + hi);
    Ok((x, f_at(&mut ctx, &expr, x)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::f64::consts::PI;

    const R: AngleMode = AngleMode::Radians;

    fn close(a: f64, b: f64, tol: f64) -> bool {
        (a - b).abs() < tol
    }

    #[test]
    fn integrates_polynomials_exactly() {
        // ∫₀¹ x² dx = 1/3
        assert!(close(
            integrate("x^2", 0.0, 1.0, R).unwrap(),
            1.0 / 3.0,
            1e-9
        ));
        // ∫₋₂³ (2x + 1) dx = [x² + x] = (9+3) - (4-2) = 10
        assert!(close(
            integrate("2x + 1", -2.0, 3.0, R).unwrap(),
            10.0,
            1e-9
        ));
        // swapped bounds negate
        assert!(close(
            integrate("x^2", 1.0, 0.0, R).unwrap(),
            -1.0 / 3.0,
            1e-9
        ));
    }

    #[test]
    fn integrates_a_transcendental() {
        // ∫₀^π sin(x) dx = 2
        assert!(close(integrate("sin(x)", 0.0, PI, R).unwrap(), 2.0, 1e-6));
    }

    #[test]
    fn derivative_of_x_cubed() {
        // d/dx x³ at 2 = 12
        assert!(close(derivative("x^3", 2.0, R).unwrap(), 12.0, 1e-4));
        // d/dx sin(x) at 0 = 1
        assert!(close(derivative("sin(x)", 0.0, R).unwrap(), 1.0, 1e-4));
    }

    #[test]
    fn finds_roots() {
        // x² − 2 has a root at √2 in [0, 2]
        assert!(close(
            root("x^2 - 2", 0.0, 2.0, R).unwrap(),
            2f64.sqrt(),
            1e-9
        ));
        // sin(x) has a root at π in [2, 4]
        assert!(close(root("sin(x)", 2.0, 4.0, R).unwrap(), PI, 1e-9));
        // no sign change -> NaN
        assert!(root("x^2 + 1", -1.0, 1.0, R).unwrap().is_nan());
    }

    #[test]
    fn finds_intersection() {
        // x² and x + 2 cross at x = 2 (y = 4) in [0, 3]
        let (x, y) = intersect("x^2", "x + 2", 0.0, 3.0, R).unwrap();
        assert!(close(x, 2.0, 1e-9));
        assert!(close(y, 4.0, 1e-8));
    }

    #[test]
    fn finds_extrema() {
        // min of (x − 1)² + 3 in [-2, 4] is (1, 3)
        let (x, y) = extremum("(x - 1)^2 + 3", -2.0, 4.0, false, R).unwrap();
        assert!(close(x, 1.0, 1e-5));
        assert!(close(y, 3.0, 1e-8));
        // max of -x² + 4x in [0, 4] is (2, 4)
        let (x, y) = extremum("-x^2 + 4x", 0.0, 4.0, true, R).unwrap();
        assert!(close(x, 2.0, 1e-5));
        assert!(close(y, 4.0, 1e-8));
    }

    #[test]
    fn value_and_bad_input() {
        assert!(close(value("x^2 + 1", 3.0, R).unwrap(), 10.0, 1e-12));
        assert!(integrate("x +", 0.0, 1.0, R).is_err());
        assert!(value("1/x", 0.0, R).unwrap().is_nan());
    }
}
