//! Pratt parser (precedence climbing).
//!
//! Binding powers (left, right):
//! ```text
//!   + -            10 / 11      left-associative
//!   * /            20 / 21      left-associative
//!   ^              30 / 29      right-associative
//!   prefix - +     ..15         so  -2^2 == -(2^2),  2^-2 == 2^(-2)
//!   postfix ! %    40           binds tighter than everything
//! ```

use crate::ast::{BinOp, Expr};
use crate::error::CalcError;
use crate::lexer::{self, Tok, Token};
use alloc::boxed::Box;
use alloc::string::String;
use alloc::vec::Vec;
use core::str::FromStr;
use rust_decimal::Decimal;

/// Lex and parse `src` into a single expression, or fail.
pub fn parse(src: &str) -> Result<Expr, CalcError> {
    let toks = lexer::lex(src)?;
    let mut p = Parser { toks, i: 0 };
    let expr = p.expr(0)?;
    match p.peek() {
        None => Ok(expr),
        Some(t) => Err(CalcError::Syntax { pos: t.pos }),
    }
}

struct Parser {
    toks: Vec<Token>,
    i: usize,
}

impl Parser {
    fn peek(&self) -> Option<&Token> {
        self.toks.get(self.i)
    }

    fn bump(&mut self) -> Option<Token> {
        let t = self.toks.get(self.i).cloned();
        if t.is_some() {
            self.i += 1;
        }
        t
    }

    fn eat(&mut self, want: &Tok) -> Result<(), CalcError> {
        match self.peek() {
            Some(t) if &t.tok == want => {
                self.i += 1;
                Ok(())
            }
            Some(t) => Err(CalcError::Syntax { pos: t.pos }),
            None => Err(CalcError::UnexpectedEnd),
        }
    }

    fn expr(&mut self, min_bp: u8) -> Result<Expr, CalcError> {
        let mut lhs = self.prefix()?;

        while let Some(tok) = self.peek() {
            if let Some(bp) = postfix_bp(&tok.tok) {
                if bp < min_bp {
                    break;
                }
                let op = self.bump().map(|t| t.tok);
                lhs = match op {
                    Some(Tok::Bang) => Expr::Factorial(Box::new(lhs)),
                    Some(Tok::Percent) => Expr::Percent(Box::new(lhs)),
                    _ => unreachable!("postfix_bp only matches ! and %"),
                };
                continue;
            }

            let Some((lbp, rbp, op)) = infix_bp(&tok.tok) else {
                break;
            };
            if lbp < min_bp {
                break;
            }
            self.i += 1;
            let rhs = self.expr(rbp)?;
            lhs = Expr::Binary {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
        }
        Ok(lhs)
    }

    fn prefix(&mut self) -> Result<Expr, CalcError> {
        let Some(tok) = self.bump() else {
            return Err(CalcError::UnexpectedEnd);
        };
        match tok.tok {
            Tok::Minus => Ok(Expr::Neg(Box::new(self.expr(15)?))),
            Tok::Plus => self.expr(15),
            Tok::Number(s) => parse_number(&s).map(Expr::Num),
            Tok::LParen => {
                let e = self.expr(0)?;
                self.eat(&Tok::RParen)?;
                Ok(e)
            }
            Tok::Ident(name) => {
                if matches!(
                    self.peek(),
                    Some(Token {
                        tok: Tok::LParen,
                        ..
                    })
                ) {
                    self.call(name)
                } else {
                    Ok(Expr::Name(name))
                }
            }
            _ => Err(CalcError::Syntax { pos: tok.pos }),
        }
    }

    fn call(&mut self, name: String) -> Result<Expr, CalcError> {
        self.eat(&Tok::LParen)?;
        let mut args = Vec::new();
        if !matches!(
            self.peek(),
            Some(Token {
                tok: Tok::RParen,
                ..
            })
        ) {
            loop {
                args.push(self.expr(0)?);
                if matches!(
                    self.peek(),
                    Some(Token {
                        tok: Tok::Comma,
                        ..
                    })
                ) {
                    self.i += 1;
                } else {
                    break;
                }
            }
        }
        self.eat(&Tok::RParen)?;
        Ok(Expr::Call { name, args })
    }
}

fn parse_number(s: &str) -> Result<Decimal, CalcError> {
    let parsed = if s.contains('e') {
        Decimal::from_scientific(s)
    } else {
        Decimal::from_str(s)
    };
    parsed.map_err(|_| CalcError::Overflow)
}

fn infix_bp(t: &Tok) -> Option<(u8, u8, BinOp)> {
    Some(match t {
        Tok::Plus => (10, 11, BinOp::Add),
        Tok::Minus => (10, 11, BinOp::Sub),
        Tok::Star => (20, 21, BinOp::Mul),
        Tok::Slash => (20, 21, BinOp::Div),
        Tok::Caret => (30, 29, BinOp::Pow),
        _ => return None,
    })
}

fn postfix_bp(t: &Tok) -> Option<u8> {
    match t {
        Tok::Bang | Tok::Percent => Some(40),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;

    fn p(s: &str) -> Expr {
        parse(s).unwrap()
    }

    #[test]
    fn precedence_shape() {
        // 1 + 2 * 3  ->  Add(1, Mul(2, 3))
        assert_eq!(
            p("1 + 2 * 3"),
            Expr::Binary {
                op: BinOp::Add,
                lhs: Box::new(Expr::Num(dec!(1))),
                rhs: Box::new(Expr::Binary {
                    op: BinOp::Mul,
                    lhs: Box::new(Expr::Num(dec!(2))),
                    rhs: Box::new(Expr::Num(dec!(3))),
                }),
            }
        );
    }

    #[test]
    fn pow_is_right_assoc() {
        assert_eq!(
            p("2 ^ 3 ^ 2"),
            Expr::Binary {
                op: BinOp::Pow,
                lhs: Box::new(Expr::Num(dec!(2))),
                rhs: Box::new(Expr::Binary {
                    op: BinOp::Pow,
                    lhs: Box::new(Expr::Num(dec!(3))),
                    rhs: Box::new(Expr::Num(dec!(2))),
                }),
            }
        );
    }

    #[test]
    fn unary_minus_looser_than_pow() {
        assert_eq!(
            p("-2 ^ 2"),
            Expr::Neg(Box::new(Expr::Binary {
                op: BinOp::Pow,
                lhs: Box::new(Expr::Num(dec!(2))),
                rhs: Box::new(Expr::Num(dec!(2))),
            }))
        );
    }

    #[test]
    fn calls() {
        assert_eq!(
            p("max(1, 2, 3)"),
            Expr::Call {
                name: "max".into(),
                args: vec![Expr::Num(dec!(1)), Expr::Num(dec!(2)), Expr::Num(dec!(3))],
            }
        );
        assert_eq!(
            p("pi()"),
            Expr::Call {
                name: "pi".into(),
                args: vec![]
            }
        );
    }

    #[test]
    fn unbalanced_parens() {
        assert!(matches!(parse("(1 + 2"), Err(CalcError::UnexpectedEnd)));
        assert!(matches!(parse("1 + 2)"), Err(CalcError::Syntax { pos: 5 })));
    }
}
