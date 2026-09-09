# CalcPlus vs. the stock Android calculator

"Stock" = Google Calculator (`com.google.android.calculator`, the one on
Pixel/AOSP). This is a running audit — where CalcPlus differs, it says so.

## Parity — same behaviour

| Stock feature | CalcPlus |
|---|---|
| Basic arithmetic with operator precedence | ✅ |
| Live result preview as you type | ✅ |
| **Expand/collapse scientific pad** — circular keys shrink to rounded rectangles, scientific rows slide in | ✅ (`sci` toggle, animated) |
| Scientific: `sin cos tan`, inverse trig, `ln`, `log`, `√`, `xʸ` (`^`), `x²`, `x⁻¹` | ✅ |
| `π`, `e`, `( )`, `x!`, `%`, `EE` (×10ⁿ entry) | ✅ |
| DEG / RAD toggle | ✅ (plus GRAD) |
| Backspace `⌫`, all-clear `AC` | ✅ |
| Negate `±` | ✅ |
| `Ans` — an operator right after `=` continues from the result | ✅ |
| History: list of past calculations, tap a **result** to insert it, tap an **expression** to reuse it | ✅ (history icon → full-screen list) |
| Clear history | ✅ (history screen and Settings) |
| Copy the result (long-press) | ✅ |
| Paste into the formula | ✅ (long-press the formula → Paste) |
| Digit grouping (`1,234,567`) | ✅ (locale-aware separator) |
| Scientific notation for very large / very small results | ✅ (`≥1e15` or `<1e-7`) |
| Error messages ("Can't divide by zero", "Not a real number", …) | ✅ |
| Formula scrolls horizontally when it's longer than the screen | ✅ |
| Landscape: scientific + numeric keypads side by side | ✅ |
| **Choose theme** — System default / Light / Dark | ✅ (Settings → Theme) |
| Keypress haptics (toggleable) | ✅ (Settings → Vibrate on keypress) |
| Rotate without losing the current expression | ✅ |
| Home-screen widget | ✅ (and it's interactive, and works on the lock screen) |

## Beyond the stock app

| | |
|---|---|
| `\|x\|` (absolute value), `gcd`, `lcm`, `nCr`, `nPr`, `root(n,x)`, hyperbolic functions | in the engine; `\|x\|` has a key |
| GRAD angle mode | — |
| Interactive lock-screen widget with responsive 1/3 · 2/3 · 3/3 sizes | — |
| **Graphing** — `y = f(x)` for up to 4 functions, pan / pinch-zoom / tap-to-trace, square auto-window, implicit multiplication (`2x`, `3(x+1)`) | — |
| Decimal (base-10) arithmetic — `0.1 + 0.2 == 0.3` exactly | — |
| Zero permissions, ~500 KB, Rust core | — |

## Deliberate differences

- **No `INV` / `2nd` toggle.** The stock app hides `sin⁻¹`, `eˣ`, `10ˣ`, `x²`
  behind an `INV` key to save space in its 5-column grid. CalcPlus is
  4-column and just gives `sin⁻¹ cos⁻¹ tan⁻¹` and `x²` their own keys — same
  capability, one less mode to think about.

## Not yet

- **Editing in the middle of the formula.** The stock app lets you tap to
  place a cursor and insert/delete anywhere. CalcPlus only appends and
  deletes at the end. This is the one real gap; it needs the formula to
  become a cursor-aware field.
- **Optional key *sounds*** (distinct from haptics).
- The overflow-menu items that only make sense inside Google's app (Send
  feedback, Rate, Help).
