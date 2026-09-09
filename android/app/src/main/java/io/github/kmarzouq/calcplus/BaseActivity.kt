// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * Applies the user's light/dark choice without pulling in AppCompat.
 *
 * The night bit of the [Configuration] is overridden in [attachBaseContext] so
 * `-night` resources resolve to the chosen theme; `SYSTEM` leaves it untouched
 * and follows the OS. Call [recreate] after changing the preference.
 */
abstract class BaseActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themed(newBase))
    }

    private fun themed(ctx: Context): Context {
        val night = when (Settings.theme(ctx)) {
            ThemeMode.SYSTEM -> return ctx
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(ctx.resources.configuration)
        config.uiMode = night or (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        return ctx.createConfigurationContext(config)
    }
}
