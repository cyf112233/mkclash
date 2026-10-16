package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihomo.core.Jobs
import mihomo.core.AppStore
import mihomo.ui.GroupDivider
import mihomo.ui.JobCard
import mihomo.ui.MiuixPage
import mihomo.ui.Screen
import mihomo.ui.TopSpacer
import mihomo.ui.formatBytes
import mihomo.ui.formatSpeed
import mihomo.ui.group
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DashboardScreen(bottomPadding: Dp, onNavigate: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val modes = remember { listOf("rule", "global", "direct") }
    val modeLabels = remember { listOf("规则", "全局", "直连") }
    val modeIndex = modes.indexOf(AppStore.mode).coerceAtLeast(0)

    MiuixPage(
        title = "mkclash",
        subtitle = if (AppStore.apiOnline) "内核运行中" else "内核未运行",
        largeTitle = true,
        bottomPadding = bottomPadding,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    AppStore.refreshAll()
                    snackbarHostState.showSnackbar("状态已刷新")
                }
            }) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
            }
        },
    ) {
        item { TopSpacer() }
        item { JobCard() }
        if (AppStore.notice.isNotEmpty() && AppStore.ready && !AppStore.transitioning) {
            item {
                Card(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (AppStore.ksuAvailable) "模块脚本调用失败" else "未运行在 KernelSU WebUI 中",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Text(
                            text = AppStore.notice,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = "重新检测",
                                onClick = { scope.launch { AppStore.retryConnection() } },
                                modifier = Modifier.weight(1f),
                            )
                            if (AppStore.connectionSource != "manual") {
                                TextButton(
                                    text = "手动连接",
                                    onClick = { onNavigate(Screen.Connection) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!AppStore.ready) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary)
                }
            }
            item { TopSpacer() }
            return@MiuixPage
        }

        item { StatusCard(scope, snackbarHostState) }

        group("运行模式") {
            OverlayDropdownPreference(
                title = "代理模式",
                summary = "rule：按规则分流 · global：全部代理 · direct：全部直连",
                items = modeLabels,
                selectedIndex = modeIndex,
                enabled = AppStore.apiOnline,
                onSelectedIndexChange = { index ->
                    scope.launch {
                        val ok = AppStore.setMode(modes[index])
                        snackbarHostState.showSnackbar(if (ok) "已切换到${modeLabels[index]}模式" else "切换失败：${AppStore.lastMessage}")
                    }
                },
            )
            GroupDivider()
            SwitchPreference(
                title = "TUN 模式",
                summary = "由内核接管全部流量，需要配置中已启用 tun",
                checked = AppStore.tunEnabled,
                enabled = AppStore.apiOnline,
                onCheckedChange = { value ->
                    scope.launch {
                        AppStore.setTun(value)
                        snackbarHostState.showSnackbar(if (AppStore.tunEnabled == value) "TUN 已${if (value) "启用" else "关闭"}" else "内核拒绝了该操作")
                    }
                },
            )
            GroupDivider()
            SwitchPreference(
                title = "允许局域网连接",
                checked = AppStore.allowLan,
                enabled = AppStore.apiOnline,
                onCheckedChange = { value ->
                    scope.launch {
                        AppStore.setAllowLan(value)
                        snackbarHostState.showSnackbar(if (AppStore.allowLan == value) "已更新" else "内核拒绝了该操作")
                    }
                },
            )
        }

        group("快捷入口") {
            ArrowPreference(
                title = "订阅管理",
                summary = "添加 / 更新机场订阅、调整订阅 UA",
                startAction = { Icon(imageVector = MiuixIcons.Link, contentDescription = null) },
                // subscriptions are a bottom tab now: switch to it instead of pushing a
                // second copy of the same screen
                onClick = { AppStore.activeTab = 2 },
            )
            GroupDivider()
            ArrowPreference(
                title = "规则列表",
                summary = "查看当前生效的分流规则",
                startAction = { Icon(imageVector = MiuixIcons.ListView, contentDescription = null) },
                onClick = { onNavigate(Screen.Rules) },
            )
            GroupDivider()
            ArrowPreference(
                title = "内核日志",
                summary = "查看最近的运行日志",
                startAction = { Icon(imageVector = MiuixIcons.Notes, contentDescription = null) },
                onClick = { onNavigate(Screen.Logs) },
            )
            GroupDivider()
            ArrowPreference(
                title = "更新 mihomo 内核",
                summary = "下载官方预编译内核并替换",
                startAction = { Icon(imageVector = MiuixIcons.Update, contentDescription = null) },
                onClick = { onNavigate(Screen.CoreUpdate) },
            )
            GroupDivider()
            ArrowPreference(
                title = "编辑配置文件",
                summary = "直接修改 config.yaml 并热重载",
                startAction = { Icon(imageVector = MiuixIcons.File, contentDescription = null) },
                onClick = { onNavigate(Screen.ConfigEditor) },
            )
        }
        item { TopSpacer() }
    }
}

@Composable
private fun StatusCard(scope: CoroutineScope, snackbarHostState: SnackbarHostState) {
    val running = AppStore.apiOnline
    Card(
        Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (running) MiuixIcons.Play else MiuixIcons.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = if (running) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            AppStore.transitioning -> Jobs.label + "…"
                            running -> "运行中"
                            else -> "已停止"
                        },
                        style = MiuixTheme.textStyles.title4,
                    )
                    Text(
                        text = when {
                            AppStore.transitioning -> "内核正在切换状态，稍后自动刷新"
                            AppStore.coreVersion.isNotEmpty() -> "内核 ${AppStore.coreVersion}"
                            else -> "等待内核响应…"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Text(
                    text = modeLabel(AppStore.mode),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }

            // intentionally full width: this separates blocks inside a content card,
            // not Preference rows inside a grouped Card (miuix lint W6 is a false positive here)
            HorizontalDivider()

            Row(Modifier.fillMaxWidth()) {
                StatCell("上传", formatBytes(AppStore.uploadTotal), formatSpeed(AppStore.uploadSpeed), Modifier.weight(1f))
                StatCell("下载", formatBytes(AppStore.downloadTotal), formatSpeed(AppStore.downloadSpeed), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatCell("活动连接", "${AppStore.activeConnections}", "", Modifier.weight(1f))
                StatCell("内存占用", formatBytes(AppStore.memoryInuse), "", Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "启动",
                    onClick = {
                        scope.launch {
                            val ok = AppStore.coreAction("start")
                            if (!ok) snackbarHostState.showSnackbar(AppStore.lastMessage.ifEmpty { "操作失败" })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                )
                TextButton(
                    text = "停止",
                    onClick = {
                        scope.launch {
                            val ok = AppStore.coreAction("stop")
                            if (!ok) snackbarHostState.showSnackbar(AppStore.lastMessage.ifEmpty { "操作失败" })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = running,
                )
                TextButton(
                    text = "重启",
                    onClick = {
                        scope.launch {
                            val ok = AppStore.coreAction("restart")
                            if (!ok) snackbarHostState.showSnackbar(AppStore.lastMessage.ifEmpty { "操作失败" })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            if (AppStore.coreBinaryInfo.isNotEmpty()) {
                Text(
                    text = AppStore.coreBinaryInfo,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}


@Composable
private fun StatCell(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(text = value, style = MiuixTheme.textStyles.title4)
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}

fun modeLabel(mode: String): String = when (mode) {
    "global" -> "全局"
    "direct" -> "直连"
    else -> "规则"
}
