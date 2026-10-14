package mihomo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.Ksu
import mihomo.core.Stream
import mihomo.ui.screens.AboutScreen
import mihomo.ui.screens.ConfigEditorScreen
import mihomo.ui.screens.ConnectionScreen
import mihomo.ui.screens.ConnectionsScreen
import mihomo.ui.screens.CoreUpdateScreen
import mihomo.ui.screens.DashboardScreen
import mihomo.ui.screens.LogsScreen
import mihomo.ui.screens.ProxiesScreen
import mihomo.ui.screens.RulesScreen
import mihomo.ui.screens.SettingsScreen
import mihomo.ui.screens.SubscriptionsScreen
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Settings

/** Routes of the app: one main shell plus a handful of secondary pages. */
sealed interface Screen {
    data object Main : Screen
    data object Connection : Screen
    data object ConfigEditor : Screen
    data object Rules : Screen
    data object CoreUpdate : Screen
    data object Logs : Screen
    data object About : Screen
}

@Composable
fun App() {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Main)) }

    AppTheme {
        BootEffects()
        val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
        val screen = stack.lastOrNull() ?: Screen.Main
        // secondary pages do not show live traffic, so stop that polling while they are open
        LaunchedEffect(screen) {
            if (screen != Screen.Main) AppStore.activeTab = -1
        }
        when (screen) {
            Screen.Main -> MainShell(onNavigate = { stack = stack + it })
            Screen.Connection -> ConnectionScreen(onBack = back)
            Screen.ConfigEditor -> ConfigEditorScreen(onBack = back)
            Screen.Rules -> RulesScreen(onBack = back)
            Screen.CoreUpdate -> CoreUpdateScreen(onBack = back)
            Screen.Logs -> LogsScreen(onBack = back)
            Screen.About -> AboutScreen(onBack = back)
        }
    }
}

/** One-shot bootstrap + the polling loops that keep the shell alive. */
@Composable
private fun BootEffects() {
    LaunchedEffect(Unit) {
        Ksu.enableEdgeToEdge(true)
        AppStore.bootstrap(this)
    }
    // Adaptive polling — now only a fallback: when the WebSocket channels are up the core
    // pushes traffic/connections/logs itself, so the panel just reconciles occasionally.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(UiPrefs.pollSeconds * 1000L)
            if (!AppStore.ready || documentHidden()) continue
            tick++
            if (Stream.connected) {
                if (tick % 10 == 0) {
                    AppStore.refreshVersion()
                    AppStore.refreshConfigs()
                    AppStore.refreshConnections()
                }
                continue
            }
            if (AppStore.activeTab == 0 || AppStore.activeTab == 3) {
                AppStore.refreshConnections()
            }
            if (!AppStore.apiOnline) AppStore.refreshVersion()
            if (tick % 5 == 0) {
                AppStore.refreshVersion()
                AppStore.refreshConfigs()
            }
        }
    }
}

private fun documentHidden(): Boolean = js(
    "(typeof document!=='undefined')&&(document.hidden===true)",
) as Boolean

private val mainTabs = listOf(
    NavigationItem("首页", MiuixIcons.Home),
    NavigationItem("代理", MiuixIcons.Layers),
    NavigationItem("订阅", MiuixIcons.Link),
    NavigationItem("连接", MiuixIcons.Merge),
    NavigationItem("设置", MiuixIcons.Settings),
)

@Composable
fun MainShell(onNavigate: (Screen) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { mainTabs.size })
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage) {
        selected = pagerState.currentPage
        AppStore.activeTab = pagerState.currentPage
    }

    // Quick entries (and anything else) can request a specific tab by setting activeTab.
    LaunchedEffect(AppStore.activeTab) {
        val target = AppStore.activeTab
        if (target in mainTabs.indices && target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(AppStore.lastMessage) {
        val message = AppStore.lastMessage
        if (message.isNotEmpty()) {
            snackbarHostState.showSnackbar(message)
            AppStore.lastMessage = ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                mainTabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            selected = index
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = item.icon,
                        label = item.label,
                    )
                }
            }
        },
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            val bottomPadding = paddingValues.calculateBottomPadding()
            when (page) {
                0 -> DashboardScreen(bottomPadding = bottomPadding, onNavigate = onNavigate)
                1 -> ProxiesScreen(bottomPadding = bottomPadding)
                2 -> SubscriptionsScreen(bottomPadding = bottomPadding)
                3 -> ConnectionsScreen(bottomPadding = bottomPadding)
                else -> SettingsScreen(bottomPadding = bottomPadding, onNavigate = onNavigate)
            }
        }
    }
}
