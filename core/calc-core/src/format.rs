//! Result formatting: rounding, trailing-zero trim, digit grouping and, for
//! very large / very small magnitudes, scientific notation.
//!
//! The engine returns a bare [`Decimal`]; the UI wants `"1,000,234"`,
//! `"0.333333333333"` or `"1.2345e18"`. Locale separators are options so the
//! Kotlin layer can pass the device locale's choices straight through.

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
    /// Switch to scientific notation once `|value|` reaches `10^sci_pos_exp`.
    pub sci_pos_exp: u32,
    /// …or drops below `10^-sci_neg_exp` (and isn't zero).
    pub sci_neg_exp: u32,
}

impl Default for FormatOptions {
    fn default() -> Self {
        Self {
            group: true,
            group_sep: ',',
            decimal_sep: '.',
            max_decimals: 12,
            sci_pos_exp: 15,
            sci_neg_exp: 7,
        }
    }
}

/// Render `value` per `opts`.
pub fn render(value: Decimal, opts: &FormatOptions) -> String {
    if value.is_zero() {
        return "0".to_string();
    }
    if wants_scientific(value, opts) {
        return render_scientific(value, opts);
    }

    let mut v = value;
    if v.scale() > opts.max_decimals {
        v = v.round_dp(opts.max_decimals);
    }
    v = v.normalize();
    fixed(&v.to_string(), opts)
}

/// Format a plain decimal string `"-1234.5"` with grouping + locale separators.
fn fixed(plain: &str, opts: &FormatOptions) -> String {
    let (neg, digits) = match plain.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, plain),
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
        let len = int_part.len();
        for (idx, ch) in int_part.char_indices() {
            if idx > 0 && (len - idx) % 3 == 0 {
                out.push(opts.group_sep);
            }
            out.push(ch);
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

fn wants_scientific(value: Decimal, opts: &FormatOptions) -> bool {
    let mag = value.abs();
    if mag >= pow10(opts.sci_pos_exp) {
        return true;
    }
    mag < pow10_neg(opts.sci_neg_exp)
}

/// `value` as `m.mmmmEexp` where `1 <= |m| < 10`.
fn render_scientific(value: Decimal, opts: &FormatOptions) -> String {
    let neg = value.is_sign_negative();
    let mut mag = value.abs();

    let mut exp: i32 = 0;
    let ten = Decimal::TEN;
    let one = Decimal::ONE;
    while mag >= ten {
        mag /= ten;
        exp += 1;
    }
    while mag < one {
        mag *= ten;
        exp -= 1;
    }

    let mantissa = fixed(
        &mag.round_dp(opts.max_decimals).normalize().to_string(),
        &FormatOptions {
            group: false,
            ..opts.clone()
        },
    );

    let mut out = String::new();
    if neg {
        out.push('-');
    }
    out.push_str(&mantissa);
    out.push('e');
    out.push_str(&exp.to_string());
    out
}

fn pow10(exp: u32) -> Decimal {
    let mut d = Decimal::ONE;
    for _ in 0..exp {
        d *= Decimal::TEN;
    }
    d
}

fn pow10_neg(exp: u32) -> Decimal {
    Decimal::ONE / pow10(exp)
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
        assert_eq!(r(dec!(0)), "0");
    }

    #[test]
    fn rounding() {
        assert_eq!(r(dec!(1) / dec!(3)), "0.333333333333");
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

    #[test]
    fn scientific_for_extremes() {
        assert_eq!(r(dec!(12345678901234567)), "1.234567890123e16");
        assert_eq!(r(dec!(-0.00000001234)), "-1.234e-8");
        // Just inside the threshold stays fixed.
        assert_eq!(r(dec!(999999999999)), "999,999,999,999");
    }
}
