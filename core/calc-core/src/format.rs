//! Result formatting: rounding, trailing-zero trim, digit grouping.
//!
//! The engine returns a bare [`Decimal`]; the UI wants `"1,000,234"` or
//! `"0.333333333333"`. Locale separators are options so the Kotlin layer can
//! pass the device locale's choices straight through.

use alloc::string::{String, ToString};
use rust_decimal::Decimal;

#[derive(Debug, Clone)]
pub struct FormatOptions {
    /// Insert `group_sep` every three integer digits.
    pub group: bool,
    pub group_sep: char,
    pub decimal_sep: char,
    /// Round to at most this many fractional digits before trimming zeros.
    pub max_decimals: u32,
}

impl Default for FormatOptions {
    fn default() -> Self {
        Self {
            group: true,
            group_sep: ',',
            decimal_sep: '.',
            max_decimals: 12,
        }
    }
}

/// Render `value` per `opts`.
pub fn render(value: Decimal, opts: &FormatOptions) -> String {
    let mut v = value;
    if v.scale() > opts.max_decimals {
        v = v.round_dp(opts.max_decimals);
    }
    v = v.normalize();

    let plain = v.to_string(); // "-1234.5", "0.5", "42"
    let (neg, digits) = match plain.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, plain.as_str()),
    };
    let (int_part, frac_part) = match digits.split_once('.') {
        Some((i, f)) => (i, Some(f)),
        None => (digits, None),
    };

    let mut out = String::new();
    if neg {
        out.push('-');
    }

    if opts.group {
        let bytes = int_part.as_bytes();
        let len = bytes.len();
        for (idx, b) in bytes.iter().enumerate() {
            if idx > 0 && (len - idx) % 3 == 0 {
                out.push(opts.group_sep);
            }
            out.push(*b as char);
        }
    } else {
        out.push_str(int_part);
    }

    if let Some(frac) = frac_part {
        out.push(opts.decimal_sep);
        out.push_str(frac);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal_macros::dec;

    fn r(d: Decimal) -> String {
        render(d, &FormatOptions::default())
    }

    #[test]
    fn grouping_and_trim() {
        assert_eq!(r(dec!(1000234)), "1,000,234");
        assert_eq!(r(dec!(-12345.6700)), "-12,345.67");
        assert_eq!(r(dec!(0.50)), "0.5");
        assert_eq!(r(dec!(42)), "42");
        assert_eq!(r(dec!(999)), "999");
    }

    #[test]
    fn rounding() {
        let third = dec!(1) / dec!(3);
        assert_eq!(r(third), "0.333333333333");
    }

    #[test]
    fn european_locale() {
        let opts = FormatOptions {
            group_sep: '.',
            decimal_sep: ',',
            ..FormatOptions::default()
        };
        assert_eq!(render(dec!(1234567.89), &opts), "1.234.567,89");
    }
}
