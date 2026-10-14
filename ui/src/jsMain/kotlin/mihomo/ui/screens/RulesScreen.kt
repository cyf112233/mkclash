package mihomo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.RuleInfo
import mihomo.ui.MiuixPage
import mihomo.ui.StateView
import mihomo.ui.TopSpacer
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Upper bound of rendered rules.
 *
 * A real config easily ships tens of thousands of rules; keeping every match in the
 * `LazyColumn` would make scrolling and filtering crawl, so the list is capped and the
 * user is told how many matches were hidden.
 */
private const val MaxRenderedRules = 300

/**
 * Rules of the running config, one card per rule.
 *
 * Rules are *not* wrapped into a single group `Card`: each row is its own `Card` item so
 * the `LazyColumn` only composes what is visible, and the 12.dp bottom padding of the card
 * is the only separator between two rules (no extra divider, which used to double up).
 */
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val rules = AppStore.rules
    val query = AppStore.ruleSearch
    var searchExpanded by remember { mutableStateOf(false) }

    // Keep the *original* index next to every rule and key the list on it: a key built from
    // the filtered index shifts as soon as the query changes, which makes the LazyColumn
    // recycle the wrong rows.
    val indexed = remember(rules) { rules.mapIndexed { index, rule -> index to rule } }
    val matched = remember(indexed, query) {
        if (query.isBlank()) {
            indexed
        } else {
            indexed.filter { (_, rule) ->
                rule.payload.contains(query, true) || rule.proxy.contains(query, true)
            }
        }
    }
    val shown = remember(matched) { matched.take(MaxRenderedRules) }

    LaunchedEffect(Unit) {
        AppStore.refreshRules()
    }

    MiuixPage(
        title = "规则",
        modifier = Modifier.imePadding(),
        subtitle = if (rules.isEmpty()) "内核未运行或尚未加载配置" else "共 ${rules.size} 条规则",
        onBack = onBack,
        ownInsets = true,
        bottomPadding = 0.dp,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    AppStore.refreshRules()
                    snackbarHostState.showSnackbar(if (AppStore.rules.isEmpty()) "未获取到规则数据" else "规则已刷新")
                }
            }) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
            }
        },
    ) {
        item { TopSpacer() }

        item {
            Card(
                Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = "规则总数",
                    summary = "当前模式：${modeText(AppStore.mode)}",
                    startAction = { Icon(imageVector = MiuixIcons.ListView, contentDescription = null) },
                    endActions = {
                        Text(text = "${rules.size}", style = MiuixTheme.textStyles.title4)
                    },
                )
            }
        }

        item {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { AppStore.ruleSearch = it },
                        onSearch = { searchExpanded = false },
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        label = "搜索规则",
                    )
                },
                onExpandedChange = { searchExpanded = it },
                expanded = searchExpanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                // Candidates are already the filtered list right below the search bar.
            }
        }

        item { SmallTitle("规则列表") }

        if (shown.isEmpty()) {
            item {
                when {
                    !AppStore.apiOnline -> StateView(
                        icon = MiuixIcons.ListView,
                        title = "未连接到内核",
                        summary = AppStore.lastMessage.ifEmpty {
                            "无法读取规则列表，请先启动内核或检查「设置 → 连接」"
                        },
                        actionText = "重试",
                        onAction = { scope.launch { AppStore.refreshRules() } },
                    )

                    query.isNotBlank() -> StateView(
                        icon = MiuixIcons.ListView,
                        title = "没有匹配的规则",
                        summary = "换一个关键词试试",
                        actionText = "清除搜索",
                        onAction = { AppStore.ruleSearch = "" },
                    )

                    else -> StateView(
                        icon = MiuixIcons.ListView,
                        title = "暂无规则数据",
                        summary = "内核未运行或尚未加载配置",
                        actionText = "重新加载",
                        onAction = { scope.launch { AppStore.refreshRules() } },
                    )
                }
            }
        }

        items(shown, key = { (index, _) -> index }) { (_, rule) ->
            RuleRow(rule)
        }

        if (matched.size > shown.size) {
            item {
                Text(
                    text = "匹配 ${matched.size} 条，仅显示前 $MaxRenderedRules 条，请用搜索缩小范围。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }
        }

        item { TopSpacer() }
    }
}

/** A single rule: its own card, spaced from its neighbours by the card padding alone. */
@Composable
private fun RuleRow(rule: RuleInfo) {
    Card(
        Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = rule.payload.ifEmpty { "-" },
            summary = "类型 ${rule.type}",
            endActions = {
                Text(
                    text = rule.proxy.ifEmpty { "-" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            },
        )
    }
}

private fun modeText(mode: String): String = when (mode) {
    "global" -> "全局"
    "direct" -> "直连"
    else -> "规则"
}
