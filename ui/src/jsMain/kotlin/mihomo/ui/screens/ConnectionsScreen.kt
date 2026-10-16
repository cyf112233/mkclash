package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.ConnInfo
import mihomo.core.Mihomo
import mihomo.ui.MiuixPage
import mihomo.ui.StateView
import mihomo.ui.TopSpacer
import mihomo.ui.formatBytes
import mihomo.ui.formatUptime
import mihomo.ui.shortenHost
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
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 渲染上限：WebView 里一次挂太多卡片会明显掉帧，超出部分只在末尾提示。
 * 内核 `/connections` 返回的顺序本身就是最近的在前，所以直接截断即可。
 */
private const val MAX_RENDERED_CONNECTIONS = 200

/**
 * 活动连接列表（底部 Tab「连接」）。
 *
 * 破坏性操作（关闭全部 / 关闭单条连接）一律先弹 [OverlayDialog] 二次确认；
 * 弹层通过 [MiuixPage] 的 `overlays` 槽挂在 `Scaffold` 内、`LazyColumn` 之外。
 */
@Composable
fun ConnectionsScreen(bottomPadding: Dp) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ConnInfo?>(null) }

    LaunchedEffect(Unit) { AppStore.refreshConnections() }

    MiuixPage(
        title = "连接",
        subtitle = "活动 ${AppStore.activeConnections} 条",
        largeTitle = true,
        bottomPadding = bottomPadding,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    AppStore.refreshConnections()
                    snackbarHostState.showSnackbar("连接列表已刷新")
                }
            }) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
            }
            IconButton(
                onClick = { confirmCloseAll = true },
                enabled = AppStore.activeConnections > 0,
            ) {
                Icon(imageVector = MiuixIcons.Delete, contentDescription = "关闭全部连接")
            }
        },
        overlays = {
            OverlayDialog(
                show = confirmCloseAll,
                title = "关闭全部连接",
                summary = "将断开全部 ${AppStore.activeConnections} 条连接，上传/下载立即中断。",
                onDismissRequest = { confirmCloseAll = false },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { confirmCloseAll = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "关闭全部",
                        onClick = {
                            confirmCloseAll = false
                            scope.launch {
                                val ok = Mihomo.closeAllConnections()
                                AppStore.refreshConnections()
                                snackbarHostState.showSnackbar(
                                    if (ok) "已关闭全部连接" else Mihomo.lastError.ifEmpty { "关闭失败" },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            ConnectionDetailDialog(
                conn = detail,
                onDismiss = { detail = null },
                onClose = { conn ->
                    scope.launch {
                        val ok = Mihomo.closeConnection(conn.id)
                        detail = null
                        AppStore.refreshConnections()
                        snackbarHostState.showSnackbar(
                            if (ok) "已关闭该连接" else Mihomo.lastError.ifEmpty { "关闭失败" },
                        )
                    }
                },
            )
        },
    ) {
        item { TopSpacer() }

        val all = AppStore.connections
        if (!AppStore.apiOnline) {
            // 内核不可达时列表必然为空，先给出「没连上」而不是「没有连接」，避免误导。
            item {
                StateView(
                    icon = MiuixIcons.Merge,
                    title = "未连接到内核",
                    summary = AppStore.lastMessage.ifEmpty {
                        "无法读取连接列表，请先启动内核或检查「设置 → 连接」"
                    },
                    actionText = "重试",
                    onAction = { scope.launch { AppStore.refreshConnections() } },
                )
            }
        } else if (all.isEmpty()) {
            item {
                StateView(
                    icon = MiuixIcons.Merge,
                    title = "当前没有活动连接",
                    summary = "流量经过代理后会显示在这里",
                    actionText = "刷新",
                    onAction = { scope.launch { AppStore.refreshConnections() } },
                )
            }
        } else {
            val shown = if (all.size > MAX_RENDERED_CONNECTIONS) {
                all.take(MAX_RENDERED_CONNECTIONS)
            } else {
                all
            }
            itemsIndexed(shown, key = { _, conn -> conn.id }) { _, conn ->
                ConnectionCard(conn = conn, onClick = { detail = conn })
            }
            if (all.size > MAX_RENDERED_CONNECTIONS) {
                item {
                    Text(
                        text = "共 ${all.size} 条连接，为保证滚动流畅仅渲染前 $MAX_RENDERED_CONNECTIONS 条",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item { TopSpacer() }
    }
}

/** 一张连接卡片：整卡可点，点开详情弹窗。 */
@Composable
private fun ConnectionCard(conn: ConnInfo, onClick: () -> Unit) {
    val meta = conn.metadata
    val chains = conn.chains.joinToString(" ← ")
    val summary = if (chains.isEmpty() && conn.rule.isEmpty()) {
        listOf(meta.network, meta.type).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "-" }
    } else {
        "$chains · ${conn.rule}"
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        BasicComponent(
            title = shortenHost(meta.displayHost),
            summary = summary.ifEmpty { "-" },
            endActions = {
                Text(
                    text = "↑${formatBytes(conn.upload)} ↓${formatBytes(conn.download)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            },
        )
    }
}

/** 单条连接详情：元数据 / 规则 / 链路 / 流量，并提供「关闭该连接」。 */
@Composable
private fun ConnectionDetailDialog(
    conn: ConnInfo?,
    onDismiss: () -> Unit,
    onClose: (ConnInfo) -> Unit,
) {
    OverlayDialog(
        show = conn != null,
        title = "连接详情",
        summary = conn?.metadata?.displayHost,
        onDismissRequest = onDismiss,
    ) {
        if (conn != null) {
            val meta = conn.metadata
            val rule = conn.rule + if (conn.rulePayload.isNotEmpty()) " (${conn.rulePayload})" else ""
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailRow("网络", listOf(meta.network, meta.type).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "-" })
                DetailRow("进程", meta.process.ifEmpty { meta.processPath }.ifEmpty { "-" })
                DetailRow("来源", joinHostPort(meta.sourceIP, meta.sourcePort))
                DetailRow("目标", joinHostPort(meta.destinationIP, meta.destinationPort))
                DetailRow("规则", rule.ifEmpty { "-" })
                DetailRow("链路", conn.chains.joinToString(" ← ").ifEmpty { "-" })
                DetailRow("流量", "↑ ${formatBytes(conn.upload)}   ↓ ${formatBytes(conn.download)}")
                DetailRow("持续时间", formatUptime(conn.start))
                if (conn.start.isNotEmpty()) {
                    DetailRow("开始时间", conn.start)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "关闭该连接",
                        onClick = { onClose(conn) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 详情里的一行「标签 : 值」。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(2f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun joinHostPort(host: String, port: String): String = when {
    host.isEmpty() -> "-"
    port.isEmpty() -> host
    else -> "$host:$port"
}
