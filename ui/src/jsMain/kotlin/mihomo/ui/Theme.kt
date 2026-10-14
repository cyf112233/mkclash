package mihomo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Applies the Miuix theme. The WebView has no reliable `isSystemInDarkTheme()`, so the
 * light/dark decision is made in [UiPrefs] (`prefers-color-scheme` + user override).
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = UiPrefs.isDark()
    val controller = remember(dark) {
        ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    MiuixTheme(controller = controller, content = content)
}
