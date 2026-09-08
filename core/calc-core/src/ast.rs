//! Expression tree produced by the parser and consumed by the evaluator.

use alloc::boxed::Box;
use alloc::string::String;
use alloc::vec::Vec;
use rust_decimal::Decimal;

#[derive(Debug, Clone, PartialEq)]
pub enum Expr {
    Num(Decimal),
    /// A bare name: either a constant (`pi`) or a variable (`x`, resolved at eval).
    Name(String),
    /// `-x`
    Neg(Box<Expr>),
    /// `x!`
    Factorial(Box<Expr>),
    /// `x%` — meaning depends on context; see [`BinOp`] handling of `+`/`-`.
    Percent(Box<Expr>),
    Binary {
        op: BinOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
    Call {
        name: String,
        args: Vec<Expr>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BinOp {
    Add,
    Sub,
    Mul,
    Div,
    Pow,
}
