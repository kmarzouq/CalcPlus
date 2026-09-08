//! Hand-written lexer. Produces a flat `Vec<Token>` (expressions are short,
//! so there is no value in a streaming lexer here).

use crate::error::CalcError;
use alloc::string::String;
use alloc::vec::Vec;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Tok {
    Number(String), // kept as text; parsed to Decimal in the parser
    Ident(String),
    Plus,
    Minus,
    Star,
    Slash,
    Caret,
    Percent,
    Bang,
    LParen,
    RParen,
    Comma,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Token {
    pub tok: Tok,
    /// Byte offset of the token's first character in the source.
    pub pos: usize,
}

/// Turn source text into tokens. Whitespace is skipped; everything else must
/// be recognised or it is a [`CalcError::BadChar`].
pub fn lex(src: &str) -> Result<Vec<Token>, CalcError> {
    let mut out = Vec::new();
    let mut chars = src.char_indices().peekable();

    while let Some(&(pos, ch)) = chars.peek() {
        let tok = match ch {
            c if c.is_whitespace() => {
                chars.next();
                continue;
            }
            '+' => single(&mut chars, Tok::Plus),
            // U+2212 MINUS SIGN and U+00D7 / U+00F7 so pasted input "just works".
            '-' | '\u{2212}' => single(&mut chars, Tok::Minus),
            '*' | '\u{00D7}' | '\u{22C5}' => single(&mut chars, Tok::Star),
            '/' | '\u{00F7}' => single(&mut chars, Tok::Slash),
            '^' => single(&mut chars, Tok::Caret),
            '%' => single(&mut chars, Tok::Percent),
            '!' => single(&mut chars, Tok::Bang),
            '(' => single(&mut chars, Tok::LParen),
            ')' => single(&mut chars, Tok::RParen),
            ',' => single(&mut chars, Tok::Comma),
            '0'..='9' | '.' => Tok::Number(lex_number(&mut chars)),
            'π' => single(&mut chars, Tok::Ident(String::from("pi"))),
            c if c.is_alphabetic() || c == '_' => Tok::Ident(lex_ident(&mut chars)),
            _ => return Err(CalcError::BadChar { pos, ch }),
        };
        out.push(Token { tok, pos });
    }
    Ok(out)
}

type Chars<'a> = core::iter::Peekable<core::str::CharIndices<'a>>;

fn single(chars: &mut Chars<'_>, tok: Tok) -> Tok {
    chars.next();
    tok
}

/// `[0-9]* ('.' [0-9]*)? ([eE] [+-]? [0-9]+)?` — at least one digit overall is
/// enforced by the parser when it calls `Decimal::from_str`.
fn lex_number(chars: &mut Chars<'_>) -> String {
    let mut s = String::new();
    let mut seen_dot = false;
    let mut seen_exp = false;

    while let Some(&(_, c)) = chars.peek() {
        match c {
            '0'..='9' => s.push(c),
            '_' => {} // digit group separator, ignored
            '.' if !seen_dot && !seen_exp => {
                seen_dot = true;
                s.push(c);
            }
            'e' | 'E' if !seen_exp => {
                seen_exp = true;
                s.push('e');
                chars.next();
                if let Some(&(_, sign @ ('+' | '-'))) = chars.peek() {
                    s.push(sign);
                    chars.next();
                }
                continue;
            }
            _ => break,
        }
        chars.next();
    }
    s
}

fn lex_ident(chars: &mut Chars<'_>) -> String {
    let mut s = String::new();
    while let Some(&(_, c)) = chars.peek() {
        if c.is_alphanumeric() || c == '_' {
            s.push(c.to_ascii_lowercase());
            chars.next();
        } else {
            break;
        }
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn toks(s: &str) -> Vec<Tok> {
        lex(s).unwrap().into_iter().map(|t| t.tok).collect()
    }

    #[test]
    fn mixed() {
        assert_eq!(
            toks("12.5 + sin(x) * 2"),
            vec![
                Tok::Number("12.5".into()),
                Tok::Plus,
                Tok::Ident("sin".into()),
                Tok::LParen,
                Tok::Ident("x".into()),
                Tok::RParen,
                Tok::Star,
                Tok::Number("2".into()),
            ]
        );
    }

    #[test]
    fn unicode_operators_and_pi() {
        assert_eq!(
            toks("2\u{00D7}\u{03C0}"),
            vec![Tok::Number("2".into()), Tok::Star, Tok::Ident("pi".into())]
        );
    }

    #[test]
    fn scientific_and_grouping() {
        assert_eq!(toks("1_000e-3"), vec![Tok::Number("1000e-3".into())]);
    }

    #[test]
    fn bad_char() {
        assert_eq!(lex("1 @ 2"), Err(CalcError::BadChar { pos: 2, ch: '@' }));
    }
}
