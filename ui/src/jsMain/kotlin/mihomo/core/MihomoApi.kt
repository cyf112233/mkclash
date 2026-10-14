package mihomo.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.js.Promise

// ---------------------------------------------------------------------------
// Models of the mihomo RESTful API (only the fields the UI actually renders).
// ---------------------------------------------------------------------------

val mihomoJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class VersionInfo(val version: String = "", val meta: Boolean = true)

@Serializable
data class DelayHistory(val time: String = "", val delay: Int = 0)

@Serializable
data class ProxyInfo(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: List<String> = emptyList(),
    val history: List<DelayHistory> = emptyList(),
    val udp: Boolean = false,
    val alive: Boolean? = null,
    val hidden: Boolean = false,
    val icon: String = "",
    val testUrl: String = "",
    val provider: String = "",
) {
    val lastDelay: Int get() = history.lastOrNull()?.delay ?: 0
    val isGroup: Boolean
        get() = type in setOf(
            "Selector", "URLTest", "Fallback", "LoadBalance", "Relay",
            "Compatible", "Pass", "Direct", "Reject",
        )
    val selectable: Boolean get() = type == "Selector" || type == "Fallback" || type == "LoadBalance"
}

@Serializable
data class ProxiesResponse(val proxies: Map<String, ProxyInfo> = emptyMap())

@Serializable
data class RuleInfo(
    val type: String = "",
    val payload: String = "",
    val proxy: String = "",
    val size: Int = -1,
)

@Serializable
data class RulesResponse(val rules: List<RuleInfo> = emptyList())

@Serializable
data class ConnMeta(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val dnsMode: String = "",
    val processPath: String = "",
    val specialProxy: String = "",
    @SerialName("process") val process: String = "",
) {
    val displayHost: String
        get() = when {
            host.isNotEmpty() -> host
            destinationIP.isNotEmpty() -> destinationIP
            else -> "-"
        }
}

@Serializable
data class ConnInfo(
    val id: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
    val metadata: ConnMeta = ConnMeta(),
)

@Serializable
data class ConnectionsResponse(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<ConnInfo> = emptyList(),
    val memory: Long = 0,
)

@Serializable
data class TunInfo(
    val enable: Boolean = false,
    val stack: String = "",
    val device: String = "",
    val autoRoute: Boolean = false,
    @SerialName("dns-hijack") val dnsHijack: List<String> = emptyList(),
)

@Serializable
data class ConfigsInfo(
    val port: Int = 0,
    @SerialName("socks-port") val socksPort: Int = 0,
    @SerialName("mixed-port") val mixedPort: Int = 0,
    @SerialName("redir-port") val redirPort: Int = 0,
    @SerialName("tproxy-port") val tproxyPort: Int = 0,
    @SerialName("allow-lan") val allowLan: Boolean = false,
    @SerialName("bind-address") val bindAddress: String = "*",
    val mode: String = "rule",
    @SerialName("log-level") val logLevel: String = "info",
    val ipv6: Boolean = false,
    @SerialName("unified-delay") val unifiedDelay: Boolean = false,
    @SerialName("tcp-concurrent") val tcpConcurrent: Boolean = false,
    @SerialName("external-controller") val externalController: String = "",
    val secret: String = "",
    val tun: TunInfo = TunInfo(),
)

@Serializable
data class ProxyProviderInfo(
    val name: String = "",
    val type: String = "",
    val vehicleType: String = "",
    val updatedAt: String = "",
)

@Serializable
data class ProxyProvidersResponse(val providers: Map<String, ProxyProviderInfo> = emptyMap())

@Serializable
data class MemoryInfo(val inuse: Long = 0, val oslimit: Long = 0)

/** One subscription managed by the module control script (`sub-list`). */
data class Subscription(
    val name: String,
    val url: String,
    val updatedAt: String,
    val nodeCount: Int,
)

/**
 * `api.json`, written next to the WebUI by the module control script. Reading it with a
 * same-origin `fetch` gives the panel host/port/secret **without** the KernelSU bridge,
 * which is what makes the UI usable as a plain (general purpose) mihomo dashboard.
 */
@Serializable
data class ApiJson(
    val host: String = "127.0.0.1",
    val port: Int = 9090,
    val secret: String = "",
    val module: String = "",
    val updated: String = "",
)

// ---------------------------------------------------------------------------
// HTTP transport. Everything goes through the KernelSU shell by default so the
// UI works no matter what the WebView thinks about mixed content / CORS.
// ---------------------------------------------------------------------------

enum class HttpBackend(val label: String) {
    CURL("curl"),
    WGET("wget"),
    FETCH("fetch"),
    NONE("none"),
}

object Http {
    var backend by mutableStateOf(HttpBackend.FETCH)
        private set
    var detected by mutableStateOf(false)
        private set

    suspend fun detect() {
        if (detected) return
        detected = true
        // Prefer the browser transport whenever there is one: `fetch` never blocks the UI
        // thread, while the host's `exec` call does (that is what made every poll and every
        // button press hitch). mihomo answers CORS including the Authorization preflight,
        // and http://127.0.0.1 counts as a trustworthy origin, so this works from the
        // KernelSU page (https://mui.kernelsu.org/…) as well as from the mihomo-hosted
        // panel. If fetch is refused, request() falls back to the shell automatically.
        if (pageOrigin().startsWith("http")) {
            backend = HttpBackend.FETCH
            return
        }
        if (!Ksu.available) {
            backend = HttpBackend.FETCH
            return
        }
        backend = when {
            hasCommand("curl") -> HttpBackend.CURL
            hasCommand("wget") -> HttpBackend.WGET
            else -> HttpBackend.FETCH
        }
    }

    /** True when the page origin is exactly the controller address. */
    fun sameOriginPanel(): Boolean {
        val origin = pageOrigin()
        if (origin.isEmpty() || !origin.startsWith("http")) return false
        return origin == "http://${Mihomo.host}:${Mihomo.port}" ||
            origin == "https://${Mihomo.host}:${Mihomo.port}"
    }

    /** Forget the detected transport (used when the controller address changes). */
    fun reset() {
        detected = false
    }

    private suspend fun hasCommand(name: String): Boolean =
        Ksu.available && Ksu.exec("command -v $name >/dev/null 2>&1 && echo yes").out == "yes"

    suspend fun request(method: String, url: String, body: String?, secret: String): String? {
        val primary = runBackend(backend, method, url, body, secret)
        if (primary != null) return primary
        // one automatic fallback: a browser context has no shell, and a WebView shell
        // context may be blocked by CORS — whichever works becomes the new backend
        val fallback = when (backend) {
            HttpBackend.FETCH -> if (Ksu.available) HttpBackend.CURL else return null
            else -> HttpBackend.FETCH
        }
        if (fallback == HttpBackend.CURL && !hasCommand("curl")) return null
        val result = runBackend(fallback, method, url, body, secret)
        if (result != null) backend = fallback
        return result
    }

    private suspend fun runBackend(
        which: HttpBackend,
        method: String,
        url: String,
        body: String?,
        secret: String,
    ): String? = when (which) {
        HttpBackend.CURL -> curl(method, url, body, secret)
        HttpBackend.WGET -> wget(method, url, body, secret)
        HttpBackend.FETCH -> fetch(method, url, body, secret)
        HttpBackend.NONE -> null
    }

    private suspend fun curl(method: String, url: String, body: String?, secret: String): String? {
        val cmd = buildString {
            append("curl -sS -m 10 -X ").append(method)
            append(" -H 'Content-Type: application/json'")
            if (secret.isNotEmpty()) append(" -H ").append(Shell.quote("Authorization: Bearer $secret"))
            if (body != null) append(" --data-binary ").append(Shell.quote(body))
            append(' ').append(Shell.quote(url))
        }
        val result = Ksu.exec(cmd)
        if (result.ok && result.stdout.isNotEmpty()) return result.stdout
        if (result.errno == 127) {
            // curl is not installed: remember wget for the next call, fall back for this one
            backend = HttpBackend.WGET
        }
        return null
    }

    private suspend fun wget(method: String, url: String, body: String?, secret: String): String? {
        val cmd = buildString {
            append("wget -q -O - --method=").append(method)
            append(" --header='Content-Type: application/json'")
            if (secret.isNotEmpty()) append(" --header=").append(Shell.quote("Authorization: Bearer $secret"))
            if (body != null) append(" --body-data=").append(Shell.quote(body))
            append(' ').append(Shell.quote(url))
        }
        val result = Ksu.exec(cmd)
        if (result.ok && result.stdout.isNotEmpty()) return result.stdout
        if (result.errno == 127) {
            backend = HttpBackend.FETCH
        }
        return null
    }

    private suspend fun fetch(method: String, url: String, body: String?, secret: String): String? {
        val impl = fetchFn ?: return null
        val headers: dynamic = js("({})")
        headers["Content-Type"] = "application/json"
        if (secret.isNotEmpty()) headers["Authorization"] = "Bearer $secret"
        val options: dynamic = js("({})")
        options["method"] = method
        options["headers"] = headers
        if (body != null) options["body"] = body
        return try {
            val promise: Promise<dynamic> = impl(url, options)
            val response: dynamic = promise.await()
            // Return the body even for 401/500: the caller turns "Unauthorized" into a
            // precise message instead of a generic "cannot connect".
            val textPromise: Promise<dynamic> = response.text()
            jsStr(textPromise.await()).ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
    }
}

/** Client for the mihomo external controller. */
object Mihomo {
    var host by mutableStateOf("127.0.0.1")
    var port by mutableStateOf(9090)
    var secret by mutableStateOf("")
    var lastError by mutableStateOf("")

    fun url(path: String): String = "http://$host:$port$path"

    suspend fun request(method: String, path: String, body: String? = null): String? {
        lastError = ""
        val text = Http.request(method, url(path), body, secret)
        if (text == null) {
            lastError = "无法连接 mihomo API（$host:$port）"
        }
        return text
    }

    suspend inline fun <reified T> getAs(path: String): T? {
        val text = request("GET", path) ?: return null
        return try {
            mihomoJson.decodeFromString(serializer<T>(), text)
        } catch (t: Throwable) {
            lastError = "响应解析失败：${t.message}"
            null
        }
    }

    /** `PATCH /configs` — used for mode / log level / tun switches. */
    suspend fun patchConfigs(jsonBody: String): Boolean =
        request("PATCH", "/configs", jsonBody) != null

    suspend fun selectProxy(group: String, name: String): Boolean {
        val body = """{"name":${jsonString(name)}}"""
        return request("PUT", "/proxies/${encodePath(group)}", body) != null
    }

    suspend fun proxyDelay(name: String, timeout: Int, testUrl: String): Int? {
        val path = "/proxies/${encodePath(name)}/delay?timeout=$timeout&url=${encodeQuery(testUrl)}"
        val text = request("GET", path) ?: return null
        // mihomo answers failed tests with {"message": "..."} instead of a delay
        if (text.contains("\"message\"")) return null
        return runCatching {
            mihomoJson.decodeFromString(DelayMessage.serializer(), text).delay
        }.getOrNull()
    }

    suspend fun groupDelay(group: String, timeout: Int, testUrl: String): Map<String, Int> {
        val path = "/group/${encodePath(group)}/delay?timeout=$timeout&url=${encodeQuery(testUrl)}"
        val text = request("GET", path) ?: return emptyMap()
        return runCatching {
            mihomoJson.decodeFromString<Map<String, Int>>(text)
        }.getOrDefault(emptyMap())
    }

    suspend fun closeConnection(id: String): Boolean =
        request("DELETE", "/connections/${encodePath(id)}") != null

    suspend fun closeAllConnections(): Boolean = request("DELETE", "/connections") != null

    suspend fun reloadConfig(path: String = Paths.CONFIG): Boolean {
        val body = """{"path":${jsonString(path)}}"""
        return request("PUT", "/configs?force=true", body) != null
    }

    suspend fun updateProxyProvider(name: String): Boolean =
        request("PUT", "/providers/proxies/${encodePath(name)}") != null
}

@Serializable
internal data class DelayMessage(val delay: Int = 0, val meanDelay: Int = 0)

/** Minimal JSON string escaping for the bodies we build by hand. */
fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') {
                sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(ch)
            }
        }
    }
    return sb.append('"').toString()
}

private fun encodePath(value: String): String = encodeUriComponentFn(value) as String

private fun encodeQuery(value: String): String = encodeUriComponentFn(value) as String

/** Parses a WebSocket frame with the same Json configuration as the REST client. */
inline fun <reified T> parseJson(text: String): T? = try {
    mihomoJson.decodeFromString(serializer<T>(), text)
} catch (t: Throwable) {
    null
}
