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

use calc_core::{evaluate_to_string, AngleMode, FormatOptions};
use jni::objects::{JClass, JString};
use jni::sys::{jchar, jdouble, jdoubleArray, jint, jstring};
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
