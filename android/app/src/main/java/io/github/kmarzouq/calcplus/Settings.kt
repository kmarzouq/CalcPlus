// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.content.Context

/** App-wide preferences (theme, angle mode, scientific panel). One file. */
object Settings {

    private const val FILE = "settings"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- theme --------------------------------------------------------------

    fun theme(ctx: Context): ThemeMode =
        ThemeMode.fromName(prefs(ctx).getString(KEY_THEME, null))

    fun setTheme(ctx: Context, mode: ThemeMode) {
        prefs(ctx).edit().putString(KEY_THEME, mode.name).apply()
    }

    // --- angle mode --------------------------------------------------------

    fun angle(ctx: Context): AngleMode =
        AngleMode.fromName(prefs(ctx).getString(KEY_ANGLE, null))

    fun setAngle(ctx: Context, mode: AngleMode) {
        prefs(ctx).edit().putString(KEY_ANGLE, mode.name).apply()
    }

    // --- scientific panel ------------------------------------------------

    fun sciOpen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SCI, false)

    fun setSciOpen(ctx: Context, open: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SCI, open).apply()
    }

    // --- haptics ---------------------------------------------------------

    fun haptics(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HAPTICS, true)

    fun setHaptics(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HAPTICS, on).apply()
    }

    // --- grapher --------------------------------------------------------

    fun graphFunctions(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_GRAPH, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    fun setGraphFunctions(ctx: Context, exprs: List<String>) {
        prefs(ctx).edit()
            .putString(KEY_GRAPH, exprs.joinToString("\n") { it.replace('\n', ' ') })
            .apply()
    }

    // --- programmer calculator -----------------------------------------

    fun progState(ctx: Context): String = prefs(ctx).getString(KEY_PROG, "").orEmpty()

    fun setProgState(ctx: Context, tokens: String) {
        prefs(ctx).edit().putString(KEY_PROG, tokens).apply()
    }

    fun progRadix(ctx: Context): Radix =
        Radix.fromName(prefs(ctx).getString(KEY_PROG_RADIX, null))

    fun setProgRadix(ctx: Context, radix: Radix) {
        prefs(ctx).edit().putString(KEY_PROG_RADIX, radix.name).apply()
    }

    fun progWord(ctx: Context): WordSize =
        WordSize.fromName(prefs(ctx).getString(KEY_PROG_WORD, null))

    fun setProgWord(ctx: Context, word: WordSize) {
        prefs(ctx).edit().putString(KEY_PROG_WORD, word.name).apply()
    }

    const val KEY_THEME = "theme_mode"
    const val KEY_ANGLE = "angle_mode"
    const val KEY_SCI = "sci_open"
    const val KEY_HAPTICS = "haptics"
    const val KEY_GRAPH = "graph_functions"
    const val KEY_PROG = "prog_tokens"
    const val KEY_PROG_RADIX = "prog_radix"
    const val KEY_PROG_WORD = "prog_word"
}

/** Light / dark selection, applied per-app (no system-wide change). */
enum class ThemeMode(val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark);

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
