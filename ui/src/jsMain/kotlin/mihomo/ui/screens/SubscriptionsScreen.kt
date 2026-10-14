package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.ui.CardHint
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.ui.JobCard
import mihomo.ui.StateView
import mihomo.ui.TopSpacer
import mihomo.ui.group
import mihomo.ui.shortenHost
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Secondary page: add / update / remove airport subscriptions.
 *
 * Subscriptions are owned by the module control script; this page is only a thin
 * front-end over `AppStore` and always reports failures through a snackbar.
 */
private const val DEFAULT_SUB_UA = "clash-verge/v1.6.0"

@Composable
fun SubscriptionsScreen(bottomPadding: Dp = 0.dp, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var ua by remember { mutableStateOf(AppStore.subscriptionUa) }
    var showErrors by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppStore.refreshSubscriptions() }

    val urlError = when {
        url.isBlank() -> "请填写订阅链接"
        !url.trim().startsWith("http://") && !url.trim().startsWith("https://") ->
            "订阅链接必须以 http:// 或 https:// 开头"
        else -> null
    }
    val nameError = if (name.isBlank()) "请填写订阅名称" else null

    fun submit() {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        scope.launch {
            if (AppStore.addSubscription(trimmedName, trimmedUrl)) {
                name = ""
                url = ""
                snackbarHostState.showSnackbar("已添加订阅「$trimmedName」")
            } else {
                snackbarHostState.showSnackbar("添加失败：${AppStore.lastMessage}")
            }
        }
    }

    MiuixPage(
        title = if (onBack == null) "订阅" else "订阅管理",
        modifier = Modifier.imePadding(),
        onBack = onBack,
        largeTitle = onBack == null,
        ownInsets = onBack != null,
        bottomPadding = bottomPadding,
        snackbarHostState = snackbarHostState,
        actions = {
            if (AppStore.busy) {
                InfiniteProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MiuixTheme.colorScheme.primary,
                    size = 20.dp,
                )
            } else {
                IconButton(
                    onClick = {
                        scope.launch {
                            AppStore.refreshSubscriptions()
                            snackbarHostState.showSnackbar("订阅列表已刷新")
                        }
                    },
                ) {
                    Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
                }
            }
        },
        overlays = {
            OverlayDialog(
                show = pendingDelete != null,
                title = "删除订阅？",
                summary = pendingDelete?.let {
                    "将从模块中移除「$it」，并删除它导入的节点。该操作不可撤销。"
                }.orEmpty(),
                onDismissRequest = { pendingDelete = null },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingDelete = null },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "删除",
                        onClick = {
                            val target = pendingDelete
                            pendingDelete = null
                            if (target != null) {
                                scope.launch {
                                    val ok = AppStore.removeSubscription(target)
                                    snackbarHostState.showSnackbar(
                                        if (ok) "已删除「$target」" else "删除失败：${AppStore.lastMessage}",
                                    )
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
        item { JobCard() }

        group("添加订阅") {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                label = "名称",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            if (showErrors && nameError != null) {
                Text(
                    text = nameError,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            GroupDivider()
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                label = "订阅链接",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            if (showErrors && urlError != null) {
                Text(
                    text = urlError,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Button(
                    onClick = {
                        if (nameError == null && urlError == null) submit() else showErrors = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !AppStore.busy,
                ) {
                    Icon(imageVector = MiuixIcons.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加")
                }
            }
        }

        group("订阅 UA") {
            TextField(
                value = ua,
                onValueChange = { ua = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                label = "下载订阅时的 User-Agent",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Text(
                text = "机场常按 UA 区分返回内容：不带 Clash 系 UA 时可能只返回 base64 节点列表，" +
                    "mihomo 就没法解析（表现为「订阅配置校验失败」）。默认 $DEFAULT_SUB_UA，" +
                    "失败时可换成机场文档写明的客户端，例如 ClashforWindows/0.19.23、mihomo/1.19.32。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                TextButton(
                    text = "保存 UA",
                    onClick = {
                        scope.launch {
                            AppStore.setSubscriptionUa(ua)
                            ua = AppStore.subscriptionUa
                            snackbarHostState.showSnackbar(AppStore.lastMessage)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !AppStore.busy && ua.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        if (AppStore.subscriptions.isEmpty()) {
            item {
                Card(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    StateView(
                        icon = MiuixIcons.Link,
                        title = "还没有订阅",
                        summary = "粘贴机场订阅链接即可一键导入配置",
                        actionText = "重新读取",
                        onAction = {
                            scope.launch {
                                AppStore.refreshSubscriptions()
                                snackbarHostState.showSnackbar("订阅列表已刷新")
                            }
                        },
                    )
                }
            }
        } else {
            item { SmallTitle("已添加") }
        }

        items(AppStore.subscriptions, key = { it.name }) { subscription ->
            Card(
                Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = subscription.name,
                    summary = "${shortenHost(subscription.url)} · 更新于 ${subscription.updatedAt} · " +
                        "${subscription.nodeCount} 个节点",
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.Link,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    endActions = {
                        TextButton(
                            text = "更新",
                            onClick = {
                                scope.launch {
                                    val ok = AppStore.updateSubscription(subscription.name)
                                    snackbarHostState.showSnackbar(
                                        if (ok) "「${subscription.name}」已更新" else "更新失败：${AppStore.lastMessage}",
                                    )
                                }
                            },
                            enabled = !AppStore.busy,
                        )
                    },
                )
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = "删除",
                        onClick = { pendingDelete = subscription.name },
                        enabled = !AppStore.busy,
                        colors = ButtonDefaults.textButtonColors(
                            textColor = MiuixTheme.colorScheme.error,
                        ),
                    )
                }
            }
        }

        group("说明") {
            CardHint(
                "更新订阅会重新下载订阅内容并覆盖 config.yaml 中由订阅生成的节点与代理组，" +
                    "但会保留外部控制端口、密钥等已有的覆盖项。更新后内核会自动重载，个别改动需要重启内核。",
            )
        }

        item { TopSpacer() }
    }
}
