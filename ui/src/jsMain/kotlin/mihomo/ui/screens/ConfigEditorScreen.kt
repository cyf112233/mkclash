package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.Paths
import mihomo.ui.MiuixPage
import mihomo.ui.StateView
import mihomo.ui.TopSpacer
import mihomo.ui.group
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Secondary page: edit `/data/adb/mihomo/config.yaml` directly.
 *
 * The multi-line [TextField] lives inside a single `LazyColumn` item, so the whole
 * editor scrolls with the list (no nested `verticalScroll`, which would fight the
 * `LazyColumn` for the scroll gesture).
 */
@Composable
fun ConfigEditorScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var text by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppStore.loadConfig() }
    LaunchedEffect(AppStore.configText) { text = AppStore.configText }

    val charCount = text.length
    val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    val dirty = text != AppStore.configText
    var showDiscard by remember { mutableStateOf(false) }

    MiuixPage(
        title = "编辑配置",
        subtitle = Paths.CONFIG,
        modifier = Modifier.imePadding(),
        onBack = { if (dirty) showDiscard = true else onBack() },
        ownInsets = true,
        bottomPadding = 0.dp,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(
                onClick = { showConfirm = true },
                enabled = !AppStore.busy && AppStore.ksuAvailable,
            ) {
                Icon(imageVector = MiuixIcons.Ok, contentDescription = "保存")
            }
            IconButton(
                onClick = {
                    scope.launch {
                        val ok = AppStore.reloadConfig()
                        snackbarHostState.showSnackbar(if (ok) "配置已重载" else "重载失败：${AppStore.lastMessage}")
                    }
                },
                enabled = !AppStore.busy && AppStore.ksuAvailable,
            ) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "重载")
            }
        },
        overlays = {
            OverlayDialog(
                show = showDiscard,
                title = "放弃未保存的修改？",
                summary = "本页还有未写入 config.yaml 的改动，返回后会丢失。",
                onDismissRequest = { showDiscard = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "继续编辑",
                        onClick = { showDiscard = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "放弃",
                        onClick = {
                            showDiscard = false
                            onBack()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                    )
                }
            }
            OverlayDialog(
                show = showConfirm,
                title = "写入并重载配置？",
                summary = "保存后立即生效且不重启内核；解析失败会保留当前配置。",
                onDismissRequest = { showConfirm = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showConfirm = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "写入并重载",
                        onClick = {
                            showConfirm = false
                            scope.launch {
                                val ok = AppStore.saveConfig(text)
                                snackbarHostState.showSnackbar(
                                    if (ok) "已保存并重载" else "失败：${AppStore.lastMessage}",
                                )
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

        if (!AppStore.ksuAvailable) {
            // Without the bridge there is no way to read or write the file: say so
            // instead of sitting on "正在读取配置…" forever.
            item {
                StateView(
                    icon = MiuixIcons.Info,
                    title = "当前环境无法编辑配置",
                    summary = AppStore.notice.ifEmpty {
                        "编辑 config.yaml 需要 KernelSU / WebUI X 宿主；也可以直接在设备上修改 " + Paths.CONFIG
                    },
                )
            }
            item { TopSpacer() }
            return@MiuixPage
        }

        item {
            Card(
                Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = "$charCount 个字符 · $lineCount 行",
                    summary = when {
                        AppStore.busy -> "正在处理…"
                        !AppStore.configLoaded -> "正在读取配置…"
                        dirty -> "已修改，尚未写入 · 点击右上角对勾保存"
                        else -> "已从 ${Paths.CONFIG} 读取"
                    },
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.UploadCloud,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }

        item { SmallTitle("config.yaml") }

        // The editor is a single (tall) list item: the LazyColumn scrolls it, so no
        // nested `verticalScroll` is needed (and it would fight the list gesture).
        item {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                minLines = 18,
                label = "config.yaml",
            )
        }

        group("其他") {
            ArrowPreference(
                title = "放弃修改并重新读取",
                summary = "丢弃本页未保存的编辑，重新从磁盘读取配置",
                startAction = { Icon(imageVector = MiuixIcons.Refresh, contentDescription = null) },
                onClick = {
                    scope.launch {
                        AppStore.loadConfig()
                        text = AppStore.configText
                        snackbarHostState.showSnackbar("已重新读取配置")
                    }
                },
            )
        }

        item {
            Text(
                text = "校验不通过时原配置保持不变。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            )
        }

        item { TopSpacer() }
    }
}
