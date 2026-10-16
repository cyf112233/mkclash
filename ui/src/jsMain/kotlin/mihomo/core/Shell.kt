package mihomo.core

/** Filesystem locations used by the Magisk/KernelSU module. */
object Paths {
    /** Stable control-script location: the UI never depends on the module id. */
    const val ROOT = "/data/adb/mihomo"
    const val CTL = "$ROOT/mihomo.sh"
    const val CONFIG = "$ROOT/config.yaml"
    const val RUN = "$ROOT/run"
    const val LOG = "$RUN/mihomo.log"
    const val TMP = "$RUN/.upload.b64"
    const val SUB_FILE = "$ROOT/subscriptions.tsv"
}

/** Helpers around `sh` invocations through the KernelSU bridge. */
object Shell {
    private const val CHUNK = 24_000

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun exec(command: String): ExecResult = Ksu.exec(command)

    /** Runs the module control script, e.g. `ctl("core", "start")`. */
    suspend fun ctl(vararg args: String): ExecResult {
        val cmd = "sh ${Paths.CTL} " + args.joinToString(" ") { quote(it) }
        return Ksu.exec(cmd)
    }

    suspend fun exists(path: String): Boolean =
        Ksu.exec("[ -e ${quote(path)} ] && echo yes").out == "yes"

    /** Reads a file through base64 so that arbitrary bytes survive the shell. */
    suspend fun readText(path: String): String? {
        val result = Ksu.exec("base64 ${quote(path)} 2>/dev/null")
        if (!result.ok || result.out.isEmpty()) return null
        return runCatching { b64DecodeUtf8(result.out) }.getOrNull()
    }

    /**
     * Writes a file as root. The payload travels as base64 chunks so no shell quoting
     * or argument-length limit can corrupt it.
     */
    suspend fun writeText(path: String, text: String): Boolean {
        val encoded = b64EncodeUtf8(text)
        Ksu.exec("mkdir -p ${Paths.RUN} && rm -f ${Paths.TMP}")
        var index = 0
        while (index < encoded.length) {
            val part = encoded.substring(index, minOf(index + CHUNK, encoded.length))
            val redirect = if (index == 0) ">" else ">>"
            val result = Ksu.exec("printf '%s' ${quote(part)} $redirect ${Paths.TMP}")
            if (!result.ok) return false
            index += CHUNK
        }
        // decode into a sibling temp file and move it into place: decoding straight onto the
        // target truncated it first, so a failed decode (no base64/busybox) emptied the file
        val target = "${quote(path)}.mkclash.tmp"
        val result = Ksu.exec(
            "base64 -d < ${Paths.TMP} > $target 2>/dev/null " +
                "|| busybox base64 -d < ${Paths.TMP} > $target 2>/dev/null; " +
                "if [ -s $target ]; then mv -f $target ${quote(path)}; else rm -f $target; false; fi; " +
                "rm -f ${Paths.TMP}",
        )
        return result.ok && exists(path)
    }

    /** Parses `key=value` lines produced by the module control script. */
    fun parseKeyValues(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toMap()
}
