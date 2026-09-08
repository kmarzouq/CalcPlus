# Calculator

A tiny, permission-free calculator for Android — built for GrapheneOS and
Accrescent, distributed via Obtainium for now.

- **No permissions.** The manifest declares none. Not `INTERNET`, nothing.
- **Small.** One Rust `.so` (~350 KB, arm64 only), no Compose, no AppCompat,
  no Material library. Release APK target: **< 2 MB**.
- **Rust core, Kotlin shell.** All arithmetic lives in `calc-core` (Rust,
  `no_std`); Kotlin does UI and the widget.
- **Interactive lock-screen widget.** Every key is a broadcast, so the keypad
  works while the device is locked. Responsive across the 1/3, 2/3 and 3/3
  lock-screen widget columns.
- **History.** Persistent, on-device, like the stock calculator.

## Status — Phases 0–2 done

| Phase | Scope | State |
|------:|-------|-------|
| 0 | Toolchain, JNI bridge, CI, reproducible-ish build | ✅ |
| 1 | Decimal calculator: `+ − × ÷ ^ %`, parens, `±`, history | ✅ |
| 2 | Interactive home/lock-screen widget, responsive sizes | ✅ |
| 3 | Scientific functions (trig, logs, constants, DEG/RAD) | later |
| 4 | TI-84 graphing (subset) | later |
| 5 | Programmer calculator (bases, bitwise, word sizes) | later |

## Layout

```
core/                 Cargo workspace
  calc-core/           lexer · Pratt parser · decimal evaluator (no_std)
  calc-ffi/            JNI bridge -> libcalc.so
android/
  app/                 Kotlin: MainActivity, widget/, input model, history
```

The engine is `Decimal` (base-10, 28–29 significant digits) — the same kind of
arithmetic a TI-84 does, so `0.1 + 0.2 == 0.3`. It is *not* a CAS: `1/3`
displays as `0.333333333333`, not as a fraction.

## Building

Prerequisites: JDK 17, Android SDK (API 36, build-tools 36, NDK
`27.2.12479018`), Rust `1.96.1` with the `aarch64-linux-android` target, and
`cargo-ndk` (`cargo install cargo-ndk`).

```sh
# Rust engine
cd core && cargo test

# App (Gradle drives cargo-ndk automatically via the :app:cargoNdkBuild task)
cd ../android
./gradlew :app:assembleRelease      # -> app/build/outputs/apk/release/
./gradlew :app:bundleRelease        # -> .aab for Accrescent later
```

### Release signing

Create `android/keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Without it, `assembleRelease` produces an **unsigned** APK (CI signs it).

## Distribution

### Obtainium (now)

Point Obtainium at this repo's GitHub Releases. Config: [`obtainium.json`](obtainium.json).
Releases are built and signed by [`.github/workflows/release.yml`](.github/workflows/release.yml)
on a `v*` tag.

### Accrescent (later)

`bundleRelease` already emits a split-APK-capable `.aab`. Remaining work:
register as a developer, wire up reproducible builds, submit.

## Lock-screen widget notes

- Widget category is `home_screen`; Android 16 QPR2 / GrapheneOS surface those
  on the lock-screen widget page. No separate keyguard widget API is used
  (that was removed in Android 5 and not brought back).
- Keypad taps are self-targeted **broadcasts** → they run while locked.
- Tapping the **display** opens the full app — the only action that prompts an
  unlock.
- Three `RemoteViews` breakpoints (narrow/medium/wide) map to the 1/3, 2/3,
  3/3 columns; they differ only in text size so nothing clips.

## App ID

`io.github.marzouq.calc` — **change this** to a namespace you control before
publishing (it must match your GitHub username for the `io.github.*` form, or
use your own domain).

## License

Build metadata assumes `GPL-3.0-or-later`. Replace with your choice
(MIT / Apache-2.0) if you prefer — update `core/*/Cargo.toml` and add a
`LICENSE` file.
