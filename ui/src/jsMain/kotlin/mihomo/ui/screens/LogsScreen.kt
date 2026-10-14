package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.Stream
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.ui.StateView
import mihomo.ui.TopSpacer
import mihomo.ui.UiPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 每次从日志文件尾部读取的行数。 */
private const val LOG_TAIL_LINES = 300

/** 自动刷新间隔（毫秒）。 */
private const val LOG_REFRESH_INTERVAL_MS = 2000L

/**
 * 日志列表在 `LazyColumn` 里的起始下标：0 是顶部呼吸位，1 是设置卡，日志行从 2 开始。
 */
private const val LOGS_ITEM_OFFSET = 2

/** 内核日志二级页。 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

    // While this page is open the device mirrors the log into the served UI directory, so
    // every refresh here is a plain fetch instead of a blocking `tail` through the exec
    // bridge. The mirror stops when the page goes away.
    DisposableEffect(Unit) {
        val tailScope = CoroutineScope(Dispatchers.Default)
        tailScope.launch { AppStore.setLogTail(true) }
        onDispose { tailScope.launch { AppStore.setLogTail(false) } }
    }
    LaunchedEffect(Unit) { AppStore.refreshLogs(LOG_TAIL_LINES) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LOG_REFRESH_INTERVAL_MS)
            AppStore.refreshLogs(LOG_TAIL_LINES)
        }
    }

    // Only follow the tail while the user is already near the bottom: otherwise every
    // refresh would yank them away from the line they are reading.
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last < 0 || last >= info.totalItemsCount - 2
        }
    }
    // prefer the WebSocket push when it is up: the file mirror stays as the fallback (and as
    // the source of the backlog when the page opens)
    val lines = if (Stream.connected && AppStore.liveLogs.isNotEmpty()) AppStore.liveLogs else AppStore.logs

    LaunchedEffect(lines.size) {
        if (UiPrefs.autoScrollLog && nearBottom && lines.isNotEmpty()) {
            listState.animateScrollToItem(LOGS_ITEM_OFFSET + lines.lastIndex)
        }
    }

    MiuixPage(
        title = "内核日志",
        onBack = onBack,
        ownInsets = true,
        bottomPadding = 0.dp,
        listState = listState,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { scope.launch { AppStore.refreshLogs(LOG_TAIL_LINES) } }) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新日志")
            }
            IconButton(
                onClick = { confirmClear = true },
                enabled = AppStore.logs.isNotEmpty(),
            ) {
                Icon(imageVector = MiuixIcons.Delete, contentDescription = "清空日志")
            }
        },
        overlays = {
            OverlayDialog(
                show = confirmClear,
                title = "清空日志",
                summary = "将删除日志文件的全部内容，操作不可撤销。",
                onDismissRequest = { confirmClear = false },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { confirmClear = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "清空",
                        onClick = {
                            confirmClear = false
                            scope.launch {
                                if (!AppStore.ksuAvailable) {
                                    snackbarHostState.showSnackbar("当前环境无法操作日志文件")
                                } else {
                                    AppStore.clearLog()
                                    snackbarHostState.showSnackbar("日志已清空")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    ) {
        item { TopSpacer() }
        item { LogOptionsCard() }

        if (lines.isEmpty()) {
            item {
                StateView(
                    icon = MiuixIcons.Notes,
                    title = "暂无日志",
                    summary = if (AppStore.ksuAvailable) {
                        "内核未运行或日志文件为空"
                    } else {
                        "当前环境无法读取日志文件"
                    },
                )
            }
        } else {
            // 日志行无法用内容做稳定 key，交给 LazyColumn 的默认 key（即下标）。
            items(lines.size) { index -> LogLine(lines[index]) }
        }
    }
}

/** 顶部设置卡：自动滚动开关 + 当前日志读取后端。 */
@Composable
private fun LogOptionsCard() {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        SwitchPreference(
            title = "自动滚动",
            summary = "停留在末尾时才跟随最新一行",
            checked = UiPrefs.autoScrollLog,
            onCheckedChange = { UiPrefs.setAutoScrollLog(it) },
        )
        GroupDivider()
        BasicComponent(
            title = "日志读取方式",
            summary = AppStore.backendLabel.ifEmpty { "尚未检测到可用后端" },
        )
    }
}

/** 一行日志：等宽字体 + 按级别着色。 */
@Composable
private fun LogLine(line: String) {
    val color = when {
        line.contains("error", ignoreCase = true) -> MiuixTheme.colorScheme.error
        line.contains("warn", ignoreCase = true) -> MiuixTheme.colorScheme.onBackground
        else -> MiuixTheme.colorScheme.onBackgroundVariant
    }
    Text(
        text = line,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        fontFamily = FontFamily.Monospace,
    )
}
