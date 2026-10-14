package mihomo.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One `/traffic` frame: bytes per second in each direction. */
@kotlinx.serialization.Serializable
data class TrafficSample(
    val up: Long = 0,
    val down: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)

@kotlinx.serialization.Serializable
data class MemorySample(val inuse: Long = 0, val oslimit: Long = 0)

/** One `/logs` frame. */
@kotlinx.serialization.Serializable
data class LogFrame(val type: String = "info", val payload: String = "")

/**
 * Live data from the core's WebSocket API.
 *
 * Polling always lags by up to one interval (and every action had to wait for the next
 * refresh before the panel looked right). The core can push instead: `/traffic`,
 * `/connections` and `/memory` are WebSocket endpoints, so the UI updates the instant
 * something happens and stops issuing HTTP requests altogether.
 *
 * Browsers cannot attach headers to a WebSocket, which is why mihomo also accepts the
 * secret as `?token=` in the query string.
 *
 * If the socket cannot be established — the host blocks ws:// from an https page, the core
 * is too old, the secret is rejected — [fallback] flips to true after a couple of attempts
 * and the caller keeps its HTTP polling. Nothing about the UI depends on the socket.
 */
object Stream {
    var connected by mutableStateOf(false)
        private set
    var fallback by mutableStateOf(false)
        private set

    /** Label for the settings/about rows. */
    val statusLabel: String
        get() = when {
            connected -> "WebSocket 实时推送"
            fallback -> "HTTP 轮询（WebSocket 不可用）"
            else -> "HTTP 轮询（正在建立实时通道…）"
        }

    // An own scope: bootstrap runs inside a LaunchedEffect whose scope is cancelled the
    // moment it returns, which would kill every reconnect attempt.
    private val scope = CoroutineScope(Dispatchers.Default)
    private var sockets: MutableList<dynamic> = mutableListOf()
    private var started = false
    private var closing = false
    private var failures = 0
    private var host = ""
    private var port = 0
    private var secret = ""

    private val openDriver: dynamic = js(
        "(function (url, onOpen, onMessage, onClose) {" +
            "  try {" +
            "    var ws = new WebSocket(url);" +
            "    ws.onopen = function () { onOpen(); };" +
            "    ws.onmessage = function (e) { onMessage(String(e.data)); };" +
            "    ws.onerror = function () {};" +
            "    ws.onclose = function () { onClose(); };" +
            "    return ws;" +
            "  } catch (e) { onClose(); return null; }" +
            "})",
    )

    // Callbacks are handed to the driver as arguments, never referenced inside the js()
    // literal — see the warning on Ksu.execDriver.
    fun start(
        onTraffic: (TrafficSample) -> Unit,
        onConnections: (ConnectionsResponse) -> Unit,
        onMemory: (MemorySample) -> Unit,
        onLog: (LogFrame) -> Unit,
        logLevel: String = "info",
    ) {
        if (started) return
        started = true
        closing = false
        host = Mihomo.host
        port = Mihomo.port
        secret = Mihomo.secret
        if (host.isEmpty() || port <= 0 || fallback) return
        open("traffic") { text -> parseJson<TrafficSample>(text)?.let(onTraffic) }
        open("connections") { text -> parseJson<ConnectionsResponse>(text)?.let(onConnections) }
        open("memory") { text -> parseJson<MemorySample>(text)?.let(onMemory) }
        open("logs?level=$logLevel") { text -> parseJson<LogFrame>(text)?.let(onLog) }
    }

    fun stop() {
        closing = true
        started = false
        connected = false
        for (ws in sockets) {
            runCatching { ws?.close() }
        }
        sockets = mutableListOf()
    }

    /** Reconnect with the current address (after the user switched controllers). */
    fun restart(
        onTraffic: (TrafficSample) -> Unit,
        onConnections: (ConnectionsResponse) -> Unit,
        onMemory: (MemorySample) -> Unit,
        onLog: (LogFrame) -> Unit,
        logLevel: String = "info",
    ) {
        stop()
        fallback = false
        failures = 0
        delayStart { start(onTraffic, onConnections, onMemory, onLog, logLevel) }
    }

    private fun delayStart(block: () -> Unit) {
        scope.launch {
            delay(300)
            block()
        }
    }

    private fun open(channel: String, onMessage: (String) -> Unit) {
        if (closing) return
        val url = buildUrl(channel) ?: return
        var opened = false
        val socket = openDriver(
            url,
            {
                opened = true
                failures = 0
                connected = true
            },
            { text: String -> onMessage(text) },
            {
                connected = false
                if (closing) return@openDriver
                if (!opened) failures += 1
                if (failures >= 3) {
                    // never came up (mixed content, rejected token, old core): stop trying and
                    // let the caller keep polling
                    fallback = true
                    return@openDriver
                }
                // the core is restarting or the socket dropped: reconnect. A socket that did
                // open resets the counter, so a genuine drop keeps retrying.
                scope.launch {
                    delay(if (opened) 1500 else 2000)
                    if (!closing) open(channel, onMessage)
                }
            },
        )
        if (socket != null) sockets.add(socket)
    }

    private fun buildUrl(channel: String): String? {
        if (host.isEmpty() || port <= 0) return null
        // `channel` may carry its own query (`logs?level=info`): the token goes after it
        val join = if (channel.contains('?')) "&" else "?"
        val token = if (secret.isEmpty()) "" else "$join" + "token=$secret"
        return "ws://$host:$port/$channel$token"
    }
}
