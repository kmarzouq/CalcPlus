//! Error type for the engine. Every failure mode is explicit; the engine
//! never panics on user input.

use alloc::string::String;
use core::fmt;

/// Everything that can go wrong turning a string into a number.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CalcError {
    /// A character that cannot begin any token, e.g. `@`.
    BadChar { pos: usize, ch: char },
    /// Tokens are well-formed but arranged illegally, e.g. `1 2` or `)(`.
    Syntax { pos: usize },
    /// Input ended while the parser still needed something, e.g. `1 +`.
    UnexpectedEnd,
    /// A name that is neither a known constant nor a known function.
    UnknownName(String),
    /// Right number of things, wrong count of arguments: `sqrt(1, 2)`.
    Arity {
        name: String,
        expected: usize,
        got: usize,
    },
    /// Division (or modulo) by zero.
    DivisionByZero,
    /// Argument outside a function's domain, e.g. `sqrt(-1)`, `ln(0)`.
    Domain(&'static str),
    /// Result (or an intermediate) exceeds `Decimal`'s range/precision.
    Overflow,
}

impl fmt::Display for CalcError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::BadChar { pos, ch } => write!(f, "unexpected character {ch:?} at {pos}"),
            Self::Syntax { pos } => write!(f, "syntax error at {pos}"),
            Self::UnexpectedEnd => write!(f, "unexpected end of expression"),
            Self::UnknownName(n) => write!(f, "unknown name '{n}'"),
            Self::Arity {
                name,
                expected,
                got,
            } => {
                write!(f, "{name} takes {expected} argument(s), got {got}")
            }
            Self::DivisionByZero => write!(f, "division by zero"),
            Self::Domain(what) => write!(f, "{what}"),
            Self::Overflow => write!(f, "number too large"),
        }
    }
}
