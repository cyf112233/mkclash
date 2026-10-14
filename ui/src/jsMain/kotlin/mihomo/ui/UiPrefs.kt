package mihomo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mihomo.core.Prefs

/** User preferences that only affect the WebUI itself (persisted in localStorage). */
object UiPrefs {
    var themeMode by mutableStateOf(Prefs.getString("ui.theme", "system"))
        private set
    var pollSeconds by mutableStateOf(Prefs.getInt("ui.poll", 3))
        private set
    var mirror by mutableStateOf(Prefs.getString("ui.mirror", ""))
        private set
    var testUrl by mutableStateOf(Prefs.getString("ui.testUrl", "http://www.gstatic.com/generate_204"))
        private set
    var showHidden by mutableStateOf(Prefs.getBool("ui.showHidden", false))
        private set
    var coreVariant by mutableStateOf(Prefs.getString("ui.variant", "arm64-v8"))
        private set
    var autoScrollLog by mutableStateOf(Prefs.getBool("ui.logAutoScroll", true))
        private set

    fun setTheme(value: String) {
        themeMode = value
        Prefs.putString("ui.theme", value)
    }

    fun setPollSeconds(value: Int) {
        pollSeconds = value.coerceIn(1, 30)
        Prefs.putInt("ui.poll", pollSeconds)
    }

    fun setMirror(value: String) {
        mirror = value.trim()
        Prefs.putString("ui.mirror", mirror)
    }

    fun setTestUrl(value: String) {
        testUrl = value.trim()
        Prefs.putString("ui.testUrl", testUrl)
    }

    fun setShowHidden(value: Boolean) {
        showHidden = value
        Prefs.putBool("ui.showHidden", value)
    }

    fun setCoreVariant(value: String) {
        coreVariant = value
        Prefs.putString("ui.variant", value)
    }

    fun setAutoScrollLog(value: Boolean) {
        autoScrollLog = value
        Prefs.putBool("ui.logAutoScroll", value)
    }

    fun isDark(): Boolean = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> mihomo.core.systemDark()
    }
}
