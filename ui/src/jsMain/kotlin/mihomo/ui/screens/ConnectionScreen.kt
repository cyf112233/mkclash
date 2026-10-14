package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import mihomo.core.Http
import mihomo.core.Mihomo
import mihomo.core.pageHref
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.ui.TopSpacer
import mihomo.ui.group
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Controller connection: auto-detection (module script → same-origin `api.json` →
 * page origin) plus a manual address, which is what turns this panel into a general
 * purpose mihomo dashboard that can point at any core.
 */
@Composable
fun ConnectionScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var host by remember { mutableStateOf(AppStore.manualHost.ifEmpty { Mihomo.host }) }
    var port by remember { mutableStateOf(AppStore.manualPort.toString()) }
    var secret by remember { mutableStateOf(AppStore.manualSecret) }
    var showReset by remember { mutableStateOf(false) }
    var showPortAuto by remember { mutableStateOf(false) }

    MiuixPage(
        title = "连接",
        modifier = Modifier.imePadding(),
        subtitle = AppStore.connectionLabel(),
        onBack = onBack,
        ownInsets = true,
        bottomPadding = 0.dp,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    val ok = AppStore.testConnection()
                    snackbarHostState.showSnackbar(
                        if (ok) "连接正常" else AppStore.lastMessage.ifEmpty { "连接失败" },
                    )
                }
            }) {
                Icon(imageVector = MiuixIcons.Refresh, contentDescription = "测试连接")
            }
        },
        overlays = {
            OverlayDialog(
                show = showPortAuto,
                title = "切换到空闲端口？",
                summary = "会改写 config.yaml 的 external-controller 并重启内核（连接会短暂断开）。" +
                    "仅在确认当前端口被其它代理占用时才需要。",
                onDismissRequest = { showPortAuto = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showPortAuto = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "切换端口",
                        onClick = {
                            showPortAuto = false
                            scope.launch {
                                AppStore.autoSelectPort()
                                host = Mihomo.host
                                port = Mihomo.port.toString()
                                secret = Mihomo.secret
                                snackbarHostState.showSnackbar(AppStore.lastMessage)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            OverlayDialog(
                show = showReset,
                title = "恢复自动连接？",
                summary = "将丢弃手动填写的地址与密钥，重新按「模块脚本 → 同源 api.json → 页面同源」自动查找内核。",
                onDismissRequest = { showReset = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showReset = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "恢复自动",
                        onClick = {
                            showReset = false
                            scope.launch {
                                AppStore.useAutoConnection()
                                host = AppStore.manualHost.ifEmpty { Mihomo.host }
                                port = Mihomo.port.toString()
                                secret = Mihomo.secret
                                snackbarHostState.showSnackbar(AppStore.lastMessage)
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

        group("当前连接") {
            BasicComponent(
                title = "连接来源",
                summary = "模块页面会自动连接，通常无需设置",
                startAction = { Icon(imageVector = MiuixIcons.Link, contentDescription = null) },
                endActions = {
                    Text(
                        text = AppStore.connectionLabel(),
                        color = if (AppStore.apiOnline) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "控制地址",
                endActions = {
                    Text(
                        text = "${Mihomo.host}:${Mihomo.port}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "状态",
                endActions = {
                    Text(
                        text = if (AppStore.apiOnline) "已连接" else AppStore.lastMessage.ifEmpty { "未连接" },
                        color = if (AppStore.apiOnline) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "HTTP 通道",
                summary = if (Http.sameOriginPanel()) "面板与 API 同源（mihomo 托管本页面）" else "通过模块脚本调用设备命令",
                endActions = {
                    Text(
                        text = AppStore.backendLabel.ifEmpty { "-" },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "页面地址",
                summary = pageHref().ifEmpty { "-" },
            )
        }

        group("模块内核") {
            BasicComponent(
                title = "端口占用",
                summary = "控制器端口被别的代理占用时，界面会连到那个实例（表现为 401）",
                startAction = { Icon(imageVector = MiuixIcons.Info, contentDescription = null) },
                endActions = {
                    Text(
                        text = when (AppStore.controllerOwner) {
                            "ours" -> "本模块内核"
                            "other" -> "被其它代理占用"
                            "none" -> "空闲"
                            else -> "-"
                        },
                        color = when (AppStore.controllerOwner) {
                            "other" -> MiuixTheme.colorScheme.error
                            "ours" -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "访问密钥",
                summary = "安装时随机生成并写入 config.yaml 的 secret",
                endActions = {
                    Text(
                        text = if (Mihomo.secret.isEmpty()) "未设置" else "已设置",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            if (AppStore.controllerOwner == "other") {
                GroupDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "检测到端口被其它代理占用",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.error,
                    )
                    Text(
                        text = "点下面的按钮会自动挑一个空闲端口写回 config.yaml 并重启内核。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
            GroupDivider()
            ArrowPreference(
                title = "自动选择空闲端口",
                summary = "9090 被占用时改用 9091…9199 中第一个可用端口，并重启内核",
                startAction = { Icon(imageVector = MiuixIcons.Tune, contentDescription = null) },
                enabled = AppStore.ksuAvailable && !AppStore.busy,
                onClick = { showPortAuto = true },
            )
        }

        group("手动指定（通用面板）") {
            TextField(
                value = host,
                onValueChange = { host = it },
                label = "API 地址，例如 192.168.1.2",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            TextField(
                value = port,
                onValueChange = { input -> port = input.filter { it.isDigit() } },
                label = "端口，例如 9090",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            TextField(
                value = secret,
                onValueChange = { secret = it },
                label = "密钥 secret（没有就留空）",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                text = "填好地址、端口、密钥后保存即可把这个面板接到任意 mihomo（局域网或远程）。" +
                    "地址与端口为空、或校验失败时不会保存，并会给出提示。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                TextButton(
                    text = "保存并连接",
                    onClick = {
                        val parsed = port.toIntOrNull() ?: 0
                        when {
                            host.isBlank() -> scope.launch { snackbarHostState.showSnackbar("请先填写 API 地址") }
                            parsed !in 1..65535 -> scope.launch { snackbarHostState.showSnackbar("端口需要在 1-65535 之间") }
                            else -> scope.launch {
                                AppStore.applyManualConnection(host, parsed, secret)
                                snackbarHostState.showSnackbar(AppStore.lastMessage)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        group("自动连接") {
            ArrowPreference(
                title = "恢复自动连接",
                summary = "丢弃手动设置，重新按模块脚本 / 同源 api.json 查找内核",
                startAction = { Icon(imageVector = MiuixIcons.Reset, contentDescription = null) },
                enabled = !AppStore.busy,
                onClick = { showReset = true },
            )
        }

        item { TopSpacer() }
    }
}
