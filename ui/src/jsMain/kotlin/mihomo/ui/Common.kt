package mihomo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mihomo.core.dateNowFn
import mihomo.core.dateParseFn
import mihomo.core.Jobs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The one page skeleton every screen uses: `Scaffold` + `TopAppBar` + `LazyColumn`.
 *
 * Pages living inside the main tab pager must pass the pager's `bottomPadding` and keep
 * `ownInsets = false`, because the shell's `Scaffold` already consumed the system insets.
 */
@Composable
fun MiuixPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    largeTitle: Boolean = false,
    ownInsets: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    /** Dialogs / bottom sheets: they must live inside the `Scaffold`, outside the `LazyColumn`. */
    overlays: (@Composable () -> Unit)? = null,
    /** Exposed for pages that scroll programmatically (the log viewer). */
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = if (ownInsets) {
            WindowInsets.systemBars.union(WindowInsets.displayCutout)
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            if (largeTitle) {
                TopAppBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = navigationIcon,
                    actions = actions ?: {},
                    scrollBehavior = scrollBehavior,
                )
            } else {
                SmallTopAppBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = navigationIcon,
                    actions = actions ?: {},
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState)
            }
        },
        floatingActionButton = { floatingActionButton?.invoke() },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + bottomPadding,
                ),
                overscrollEffect = null,
            ) {
                content()
            }
            overlays?.invoke()
        }
    }
}

/** One `SmallTitle` + `Card` group inside a [LazyColumn]. */
fun LazyListScope.group(title: String? = null, content: @Composable () -> Unit) {
    if (title != null) {
        item { SmallTitle(title) }
    }
    item {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            content()
        }
    }
}

/** Divider used *between* rows of a group card (16.dp inset, aligned with titles). */
@Composable
fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

@Composable
fun TopSpacer() {
    Spacer(Modifier.size(12.dp))
}

/** Full-page empty / error / permission state. */
@Composable
fun StateView(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(text = title, color = MiuixTheme.colorScheme.onBackground)
        if (summary != null) {
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        if (actionText != null && onAction != null) {
            TextButton(
                text = actionText,
                onClick = onAction,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** Compact empty hint placed inside a card. */
@Composable
fun CardHint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val rounded = (value * 10).toInt() / 10.0
    return "$rounded ${units[index]}"
}

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

fun formatUptime(startIso: String): String {
    val start = dateParseFn(startIso) as Double
    if (start.isNaN()) return "-"
    val seconds = ((dateNowFn() as Double) - start) / 1000.0
    if (seconds < 0) return "-"
    val total = seconds.toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m${secs}s"
        else -> "${secs}s"
    }
}

@Composable
fun delayColor(delay: Int): Color = when {
    delay <= 0 -> MiuixTheme.colorScheme.onBackgroundVariant
    delay < 200 -> MiuixTheme.colorScheme.primary
    delay < 500 -> MiuixTheme.colorScheme.onBackground
    else -> MiuixTheme.colorScheme.error
}

fun shortenHost(value: String, max: Int = 42): String =
    if (value.length <= max) value else value.take(max - 1) + "…"

/**
 * Progress of a detached module job (see [mihomo.core.Jobs]).
 *
 * The work runs outside the WebView thread, so this card is what the user watches instead
 * of a frozen screen: it appears while a job is running and is simply absent otherwise.
 */
@Composable
fun JobCard(modifier: Modifier = Modifier) {
    if (!Jobs.busy) return
    Card(modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfiniteProgressIndicator(
                color = MiuixTheme.colorScheme.primary,
                size = 20.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(text = Jobs.label, style = MiuixTheme.textStyles.body1)
                if (Jobs.current.message.isNotEmpty()) {
                    Text(
                        text = Jobs.current.message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}
