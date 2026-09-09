// SPDX-License-Identifier: GPL-2.0-only
//! JNI surface for `calc-core`, built as `libcalc.so`.
//!
//! Kotlin side: `io.github.kmarzouq.calcplus.NativeBridge`.
//!
//! Contract:
//! * `nativeEval` returns the formatted result string on success.
//! * On any evaluation error it throws `java.lang.ArithmeticException` whose
//!   message is a short human-readable reason, and returns `null`.
//! * A panic in the engine (should never happen) throws `RuntimeException`
//!   instead of unwinding across the FFI boundary (which is UB).

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

use calc_core::{evaluate_to_string, programmer, AngleMode, FormatOptions, NumFormat};
use jni::objects::{JClass, JString};
use jni::sys::{jchar, jdouble, jdoubleArray, jint, jlong, jstring};
use jni::JNIEnv;

fn angle_of(mode: jint) -> AngleMode {
    match mode {
        1 => AngleMode::Degrees,
        2 => AngleMode::Gradians,
        _ => AngleMode::Radians,
    }
}

/// `NativeBridge.nativeEval(expr, groupSep, decimalSep, maxDecimals, angleMode)`.
///
/// `groupSep == 0` disables digit grouping. Separators are passed as UTF-16
/// code units so the Kotlin layer can forward the device locale's choices.
/// `angleMode`: 0 = radians, 1 = degrees, 2 = gradians.
#[no_mangle]
pub extern "system" fn Java_io_github_kmarzouq_calcplus_NativeBridge_nativeEval<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    expr: JString<'l>,
    group_sep: jchar,
    decimal_sep: jchar,
    max_decimals: jint,
    angle_mode: jint,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(
        || -> Result<Option<String>, jni::errors::Error> {
            let input: String = env.get_string(&expr)?.into();
            let opts = FormatOptions {
                group: group_sep != 0,
                group_sep: char::from_u32(u32::from(group_sep)).unwrap_or(','),
                decimal_sep: char::from_u32(u32::from(decimal_sep)).unwrap_or('.'),
                max_decimals: max_decimals.clamp(0, 28) as u32,
                ..FormatOptions::default()
            };
            match evaluate_to_string(&input, &opts, angle_of(angle_mode)) {
                Ok(s) => Ok(Some(s)),
                Err(e) => {
                    env.throw_new("java/lang/ArithmeticException", e.to_string())?;
                    Ok(None)
                }
            }
        },
    ));

    match outcome {
        Ok(Ok(Some(s))) => env
            .new_string(s)
            .map(JString::into_raw)
            .unwrap_or(ptr::null_mut()),
        Ok(Ok(None)) => ptr::null_mut(),
        Ok(Err(e)) => {
            let _ = env.throw_new("java/lang/RuntimeException", format!("jni error: {e}"));
            ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "calc engine panicked");
            ptr::null_mut()
        }
    }
}

/// `NativeBridge.nativeSample(expr, xMin, xMax, n, angleMode)` — sample a
/// function of `x` for graphing. Returns a `double[]` of `n` y-values (`NaN`
/// where the function is undefined); an **empty** array means the expression
/// itself was malformed.
#[no_mangle]
pub extern "system" fn Java_io_github_kmarzouq_calcplus_NativeBridge_nativeSample<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    expr: JString<'l>,
    x_min: jdouble,
    x_max: jdouble,
    n: jint,
    angle_mode: jint,
) -> jdoubleArray {
    let count = n.max(0) as usize;
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<f64>, ()> {
        let input: String = env.get_string(&expr).map_err(|_| ())?.into();
        calc_core::sample(&input, x_min, x_max, count, angle_of(angle_mode)).map_err(|_| ())
    }));

    let ys: Vec<f64> = match outcome {
        Ok(Ok(v)) => v,
        _ => Vec::new(),
    };

    match env.new_double_array(ys.len() as jint) {
        Ok(arr) => {
            if !ys.is_empty() {
                let _ = env.set_double_array_region(&arr, 0, &ys);
            }
            arr.into_raw()
        }
        Err(_) => ptr::null_mut(),
    }
}

/// `NativeBridge.nativeProgEval(expr, mode, p1, p2, p3)` — evaluate a
/// programmer-calculator expression under a number format. `mode`: `0` = signed
/// int, `1` = unsigned int, `2` = float. For int, `p1` is the width code
/// (`0`=8, `1`=16, `2`=32, else 64). For float, `p1`/`p2`/`p3` are the sign /
/// exponent / mantissa bit counts. Returns the result's raw bit pattern as a
/// `long`. Throws `java.lang.ArithmeticException` on any error.
#[no_mangle]
pub extern "system" fn Java_io_github_kmarzouq_calcplus_NativeBridge_nativeProgEval<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    expr: JString<'l>,
    mode: jint,
    p1: jint,
    p2: jint,
    p3: jint,
) -> jlong {
    let outcome = catch_unwind(AssertUnwindSafe(
        || -> Result<Option<u64>, jni::errors::Error> {
            let input: String = env.get_string(&expr)?.into();
            let fmt = NumFormat::from_wire(mode, p1, p2, p3);
            match programmer::evaluate_fmt(&input, fmt) {
                Ok(v) => Ok(Some(v)),
                Err(e) => {
                    env.throw_new("java/lang/ArithmeticException", e.to_string())?;
                    Ok(None)
                }
            }
        },
    ));

    match outcome {
        Ok(Ok(Some(v))) => v as jlong,
        Ok(Ok(None)) => 0,
        Ok(Err(e)) => {
            let _ = env.throw_new("java/lang/RuntimeException", format!("jni error: {e}"));
            0
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "calc engine panicked");
            0
        }
    }
}

/// `NativeBridge.nativeProgFormat(bits, mode, p1, p2, p3)` — render a raw bit
/// pattern as its human value string (signed/unsigned decimal, or the float's
/// decimal value). Same `mode`/`p*` encoding as `nativeProgEval`.
#[no_mangle]
pub extern "system" fn Java_io_github_kmarzouq_calcplus_NativeBridge_nativeProgFormat<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    bits: jlong,
    mode: jint,
    p1: jint,
    p2: jint,
    p3: jint,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let fmt = NumFormat::from_wire(mode, p1, p2, p3);
        programmer::format_value(bits as u64, fmt)
    }));

    match outcome {
        Ok(s) => env
            .new_string(s)
            .map(JString::into_raw)
            .unwrap_or(ptr::null_mut()),
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "calc engine panicked");
            ptr::null_mut()
        }
    }
}

/// `NativeBridge.nativeVersion()` — cheap smoke test that the `.so` loaded.
#[no_mangle]
pub extern "system" fn Java_io_github_kmarzouq_calcplus_NativeBridge_nativeVersion<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.new_string(env!("CARGO_PKG_VERSION"))
        .map(JString::into_raw)
        .unwrap_or(ptr::null_mut())
}
