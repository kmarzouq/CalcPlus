// SPDX-License-Identifier: GPL-2.0-only
package io.github.kmarzouq.calcplus

import android.os.Bundle
import android.widget.Toast
import io.github.kmarzouq.calcplus.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {

    private lateinit var ui: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.back.setOnClickListener { finish() }

        val ids = mapOf(
            ThemeMode.SYSTEM to ui.themeSystem.id,
            ThemeMode.LIGHT to ui.themeLight.id,
            ThemeMode.DARK to ui.themeDark.id,
        )
        ui.themeGroup.check(ids.getValue(Settings.theme(this)))
        ui.themeGroup.setOnCheckedChangeListener { _, checked ->
            val mode = ids.entries.first { it.value == checked }.key
            if (mode != Settings.theme(this)) {
                Settings.setTheme(this, mode)
                recreate()
            }
        }

        ui.haptics.isChecked = Settings.haptics(this)
        ui.haptics.setOnCheckedChangeListener { _, on -> Settings.setHaptics(this, on) }

        ui.clearHistory.setOnClickListener { confirmClearHistory() }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        ui.aboutVersion.text = getString(R.string.about_version, version)
        ui.aboutEngine.text = getString(R.string.about_engine, CalcEngine.version())
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                History(this).clear()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
