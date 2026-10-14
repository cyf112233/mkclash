package mihomo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import mihomo.ui.App

/**
 * Web entry point.
 *
 * The WebView loads `index.html`, which provides `<div id="compose-root">`; Compose
 * renders its canvas into that container.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("compose-root") {
        App()
    }
    // the static "loading" hint is no longer needed once Compose took over
    js("var b=document.getElementById('boot');if(b&&b.parentNode){b.parentNode.removeChild(b);}")
}
