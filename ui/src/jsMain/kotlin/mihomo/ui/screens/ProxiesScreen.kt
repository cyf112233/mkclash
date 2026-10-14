package mihomo.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.ProxyInfo
import mihomo.ui.CardHint
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.ui.StateView
import mihomo.ui.TopSpacer
import mihomo.ui.delayColor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Members a group shows before it has to be expanded by hand. */
private const val CollapsedMemberCount = 12

/** Members a group renders at most while expanded. */
private const val ExpandedMemberLimit = 60

/** How many groups the top bar "test all" action measures in one run. */
private const val BatchGroupCount = 8

/**
 * Proxy groups and their nodes.
 *
 * Groups come first (a group is an entry with members), single nodes after them, and the
 * built-in `GLOBAL` group last; `DIRECT` / `REJECT` are never shown.
 *
 * Which groups are expanded lives here, not inside the card: a `remember` inside a
 * `LazyColumn` item is dropped as soon as the item scrolls out of the viewport.
 */
@Composable
fun ProxiesScreen(bottomPadding: Dp) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val groups = remember(AppStore.proxies) {
        AppStore.proxies.values
            .filter { it.name != "DIRECT" && it.name != "REJECT" && !it.hidden }
            .sortedWith(compareBy<ProxyInfo> { order(it) }.thenBy { it.name })
    }
    val groupCount = remember(groups) { groups.count { it.all.isNotEmpty() } }

    LaunchedEffect(Unit) {
        AppStore.refreshProxies()
    }

    MiuixPage(
        title = "代理",
        subtitle = if (AppStore.proxies.isEmpty()) {
            "内核未运行或尚未加载配置"
        } else {
            "$groupCount 个代理组 · ${AppStore.proxies.size} 个节点"
        },
        largeTitle = true,
        bottomPadding = bottomPadding,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    val testable = groups.filter { it.all.isNotEmpty() }
                    val targets = testable.take(BatchGroupCount)
                    if (targets.isEmpty()) {
                        snackbarHostState.showSnackbar("没有可测速的代理组")
                    } else {
                        // Say how many groups really got a request: the run is capped.
                        val message = if (testable.size > targets.size) {
                            "已对 ${targets.size} 个组发起测速（共 ${testable.size} 个组，单次最多 $BatchGroupCount 个）"
                        } else {
                            "已对 ${targets.size} 个组发起测速"
                        }
                        snackbarHostState.showSnackbar(message)
                        targets.forEach { AppStore.testGroupDelay(it.name) }
                    }
                }
            }) {
                Icon(imageVector = MiuixIcons.Stopwatch, contentDescription = "全部测速")
            }
            IconButton(onClick = {
                scope.launch {
                    AppStore.refreshProxies()
                    snackbarHostState.showSnackbar(if (AppStore.proxies.isEmpty()) "未获取到代理数据" else "代理列表已刷新")
                }
            }) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
            }
        },
    ) {
        item { TopSpacer() }

        if (AppStore.proxies.isEmpty()) {
            item {
                if (!AppStore.apiOnline) {
                    StateView(
                        icon = MiuixIcons.Layers,
                        title = "未连接到内核",
                        summary = AppStore.lastMessage.ifEmpty {
                            "无法读取代理列表，请先启动内核或检查「设置 → 连接」"
                        },
                        actionText = "重试",
                        onAction = { scope.launch { AppStore.refreshProxies() } },
                    )
                } else {
                    StateView(
                        icon = MiuixIcons.Layers,
                        title = "暂无代理数据",
                        summary = "内核未运行或尚未加载配置",
                        actionText = "重新加载",
                        onAction = { scope.launch { AppStore.refreshProxies() } },
                    )
                }
            }
        } else {
            groups.forEach { proxy ->
                val expanded = proxy.name in expandedGroups
                item(key = "group-title-${proxy.name}") {
                    SmallTitle("${proxy.name} · ${proxy.type}")
                }
                item(key = "group-${proxy.name}") {
                    Card(
                        Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ProxyGroupBody(
                            proxy = proxy,
                            expanded = expanded,
                            onToggle = {
                                expandedGroups = if (expanded) {
                                    expandedGroups - proxy.name
                                } else {
                                    expandedGroups + proxy.name
                                }
                            },
                            scope = scope,
                            snackbarHostState = snackbarHostState,
                        )
                    }
                }
            }
        }

        item { TopSpacer() }
    }
}

/** One group card: current node, delay test, then the member rows. */
@Composable
private fun ProxyGroupBody(
    proxy: ProxyInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    val members = proxy.all

    // A Selector / URLTest group usually has no delay of its own — fall back to the
    // delay of the node it currently points at.
    val selectedDelay = AppStore.proxies[proxy.now]?.lastDelay ?: 0
    val shownDelay = if (proxy.lastDelay > 0) proxy.lastDelay else selectedDelay

    ArrowPreference(
        title = if (members.isEmpty()) proxy.name else "当前节点",
        summary = if (members.isEmpty()) {
            "${proxy.type}${if (proxy.udp) " · UDP" else ""}"
        } else {
            proxy.now.ifEmpty { "未选择" }
        },
        endActions = {
            Text(
                text = delayLabel(shownDelay),
                color = delayColor(shownDelay),
                style = MiuixTheme.textStyles.footnote1,
            )
        },
        onClick = onToggle,
    )

    if (members.isEmpty()) {
        return
    }

    GroupDivider()
    ArrowPreference(
        title = "测试该组延迟",
        summary = "共 ${members.size} 个节点",
        onClick = {
            scope.launch {
                AppStore.testGroupDelay(proxy.name)
                snackbarHostState.showSnackbar("已发起测速")
            }
        },
    )

    val collapsible = members.size > CollapsedMemberCount
    val visible = when {
        !collapsible -> members
        !expanded -> members.take(CollapsedMemberCount)
        else -> members.take(ExpandedMemberLimit)
    }
    visible.forEach { name ->
        val delay = AppStore.proxies[name]?.lastDelay ?: 0
        GroupDivider()
        RadioButtonPreference(
            title = name,
            summary = delaySummary(delay),
            selected = name == proxy.now,
            enabled = proxy.selectable,
            endActions = {
                Text(
                    text = delayLabel(delay),
                    color = delayColor(delay),
                    style = MiuixTheme.textStyles.footnote1,
                )
            },
            onClick = {
                scope.launch {
                    AppStore.selectProxy(proxy.name, name)
                }
            },
        )
    }

    if (expanded && members.size > ExpandedMemberLimit) {
        GroupDivider()
        CardHint("仅显示前 $ExpandedMemberLimit 个节点")
    }

    if (collapsible) {
        GroupDivider()
        ArrowPreference(
            title = if (expanded) "收起" else "展开剩余 ${members.size - CollapsedMemberCount} 个",
            onClick = onToggle,
        )
    }
}

/** Groups first, then single nodes, then the built-in `GLOBAL` group. */
private fun order(proxy: ProxyInfo): Int = when {
    proxy.all.isNotEmpty() -> 0
    proxy.name == "GLOBAL" -> 2
    else -> 1
}

private fun delayLabel(delay: Int): String = if (delay > 0) "$delay ms" else "未测"

private fun delaySummary(delay: Int): String = if (delay > 0) "延迟 $delay ms" else "尚未测速"
