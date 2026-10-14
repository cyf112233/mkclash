#!/system/bin/sh
#---------------------------------------------------------------------
# mihomo module control script
#
# Installed to /data/adb/mihomo/mihomo.sh and called by:
#   * post-fs-data.sh / service.sh / action.sh (module lifecycle)
#   * the WebUI (through KernelSU's exec bridge)
#
# Every command prints `key=value` lines so the UI can parse the result.
#---------------------------------------------------------------------

ROOT=/data/adb/mihomo
BIN=$ROOT/bin/mihomo
CONF=$ROOT/config.yaml
RUN=$ROOT/run
LOG=$RUN/mihomo.log
PIDFILE=$RUN/mihomo.pid
STARTLOCK=$RUN/.start.lock
STAGED=$RUN/config.new.yaml
JOBDIR=$RUN/jobs
SELF=$0
VERSIONCACHE=$RUN/version.cache
CONFCHECK=$RUN/config.checked
SUBS=$ROOT/subscriptions.tsv
UA_FILE=$ROOT/subscription.ua
AUTOSTART=$ROOT/autostart
GEO=$ROOT
# Where the compiled WebUI lives (the KernelSU host serves this directory).
WEBROOT=/data/adb/modules/mkclash/webroot
# mihomo serves the panel itself through `external-ui`, but it refuses paths outside its
# working directory ("path is not subpath of home directory or SAFE_PATHS", fatal at
# startup). post-fs-data copies the panel here, and SAFE_PATHS is exported as a second
# safety net for configurations that still point at the module directory.
UIHOME=$ROOT/ui
export SAFE_PATHS="$UIHOME:$WEBROOT${SAFE_PATHS:+:$SAFE_PATHS}"

mkdir -p "$ROOT" "$RUN" "$ROOT/bin" 2>/dev/null

#---------------------------------------------------------------------
# helpers
#---------------------------------------------------------------------

# Lightweight audit trail (bounded): makes it obvious whether the WebUI can actually
# reach the control script through the host's exec bridge.
if [ -d "$RUN" ]; then
    echo "$(date '+%F %T') ${1:-?}" >>"$RUN/exec.log" 2>/dev/null
    lines=$(wc -l <"$RUN/exec.log" 2>/dev/null)
    if [ -n "$lines" ] && [ "$lines" -gt 300 ]; then
        tail -n 200 "$RUN/exec.log" >"$RUN/exec.log.tmp" 2>/dev/null &&
            mv -f "$RUN/exec.log.tmp" "$RUN/exec.log" 2>/dev/null
    fi
fi

have() { command -v "$1" >/dev/null 2>&1; }

# Built-in GitHub accelerators, tried after the user's own prefix (and after a direct
# attempt). api.github.com / github.com are unreachable on plenty of networks.
BUILTIN_MIRRORS="https://ghfast.top/ https://gh-proxy.com/ https://ghproxy.net/"

# Airport subscriptions are User-Agent sensitive: without a Clash-like UA many providers
# answer with a base64 node list (v2ray format) instead of Clash YAML, which then fails
# validation. Override with `mihomo.sh sub-ua <value>`.
DEFAULT_SUB_UA="clash-verge/v1.6.0"

sub_ua() {
    value=""
    [ -f "$UA_FILE" ] && value=$(head -n 1 "$UA_FILE" 2>/dev/null | tr -d '\r\n')
    [ -z "$value" ] && value="$DEFAULT_SUB_UA"
    echo "$value"
}

download_sub() { # url destfile
    ua=$(sub_ua)
    if have curl; then
        curl -L -f -sS --connect-timeout 20 --retry 2 -A "$ua" -o "$2" "$1"
    elif have wget; then
        wget -q -U "$ua" -O "$2" "$1"
    elif [ -x /data/adb/magisk/busybox ]; then
        /data/adb/magisk/busybox wget -q -U "$ua" -O "$2" "$1"
    else
        return 127
    fi
}

download_to() { # url destfile
    if have curl; then
        curl -L -f -sS --connect-timeout 12 --retry 1 -o "$2" "$1"
    elif have wget; then
        wget -q -O "$2" "$1"
    elif [ -x /data/adb/magisk/busybox ]; then
        /data/adb/magisk/busybox wget -q -O "$2" "$1"
    else
        return 127
    fi
}

fetch_stdout() { # url
    if have curl; then
        curl -L -f -sS --connect-timeout 15 --retry 1 "$1"
    elif have wget; then
        wget -q -O - "$1"
    elif [ -x /data/adb/magisk/busybox ]; then
        /data/adb/magisk/busybox wget -q -O - "$1"
    else
        return 127
    fi
}

# final URL after redirects — used to read the latest release tag without hitting
# the (heavily rate limited) GitHub API
head_location() { # url
    if have curl; then
        curl -sSI -o /dev/null -w '%{redirect_url}' --connect-timeout 15 "$1"
    elif have wget; then
        wget -q --server-response --spider --timeout=15 "$1" 2>&1 \
            | awk 'tolower($1) == "location:" {print $2}' | tail -n 1 | tr -d '\r'
    elif [ -x /data/adb/magisk/busybox ]; then
        /data/adb/magisk/busybox wget -q --server-response --spider "$1" 2>&1 \
            | awk 'tolower($1) == "location:" {print $2}' | tail -n 1 | tr -d '\r'
    else
        return 127
    fi
}

# Fetch a github.com URL through the user's prefix first, then directly.
# $MIRROR_PREFIX is set by the command that needs it.
fetch_github() { # url
    if [ -n "$MIRROR_PREFIX" ]; then
        out=$(fetch_stdout "${MIRROR_PREFIX}$1" 2>/dev/null)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
    fi
    fetch_stdout "$1" 2>/dev/null
}

head_github() { # url
    if [ -n "$MIRROR_PREFIX" ]; then
        out=$(head_location "${MIRROR_PREFIX}$1" 2>/dev/null)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
    fi
    head_location "$1" 2>/dev/null
}

# Download a github.com URL, walking the prefix list until one works:
# user prefix (if any) -> direct -> built-in accelerators.
download_github() { # url destfile
    tried=""
    if [ -n "$MIRROR_PREFIX" ]; then
        url="${MIRROR_PREFIX}$1"
        if download_to "$url" "$2" && [ -s "$2" ]; then
            echo "downloaded_from=$url"
            return 0
        fi
        tried="$tried $url"
        rm -f "$2"
    fi
    if download_to "$1" "$2" && [ -s "$2" ]; then
        echo "downloaded_from=$1"
        return 0
    fi
    tried="$tried $1"
    rm -f "$2"
    for builtin in $BUILTIN_MIRRORS; do
        [ "$builtin" = "$MIRROR_PREFIX" ] && continue
        url="${builtin}$1"
        if download_to "$url" "$2" && [ -s "$2" ]; then
            echo "downloaded_from=$url"
            return 0
        fi
        tried="$tried $url"
        rm -f "$2"
    done
    echo "error=下载失败，已尝试：$tried"
    return 1
}

#---------------------------------------------------------------------
# controller port / secret
#---------------------------------------------------------------------

# A TCP port is "in use" when something already listens on 127.0.0.1:<port>.
# netstat/lsof are not reliably present on Android, so /proc/net/tcp is parsed.
port_in_use() { # port
    hex=$(printf '%04X' "$1" 2>/dev/null) || return 1
    for table in /proc/net/tcp /proc/net/tcp6; do
        [ -r "$table" ] || continue
        if awk -v p=":$hex" 'NR > 1 && $4 == "0A" { n = split($2, a, ":"); if ((":" a[n]) == p) { found = 1; exit } } END { exit !found }' "$table"; then
            return 0
        fi
    done
    return 1
}

# True when the configured controller answers with our secret (i.e. it is our core).
controller_is_ours() {
    have curl || return 1
    probe=$(curl -s -m 2 -H "Authorization: Bearer $(api_secret)" \
        "http://$(api_host):$(api_port)/version" 2>/dev/null)
    case "$probe" in
        *'"version"'*) return 0 ;;
    esac
    return 1
}

write_controller_port() { # port
    host=$(api_host)
    line="external-controller: $host:$1"
    if grep -qE '^[[:space:]]*external-controller[[:space:]]*:' "$CONF" 2>/dev/null; then
        sed -i "s|^[[:space:]]*external-controller[[:space:]]*:.*|$line|" "$CONF"
    else
        printf '%s\n' "$line" >>"$CONF"
    fi
}

# Make sure the controller secret exists (a random one is generated on install).
ensure_secret() {
    if [ -z "$(api_secret)" ]; then
        generated=$(head -c 8 /dev/urandom 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
        [ -z "$generated" ] && generated=$(date +%s)$RANDOM
        if grep -qE '^[[:space:]]*secret[[:space:]]*:' "$CONF" 2>/dev/null; then
            sed -i "s|^[[:space:]]*secret[[:space:]]*:.*|secret: $generated|" "$CONF"
        else
            printf 'secret: %s\n' "$generated" >>"$CONF"
        fi
        echo "secret_generated=1"
    fi
}

cmd_port_status() {
    port=$(api_port)
    if controller_is_ours; then
        owner=ours
    elif port_in_use "$port"; then
        owner=other
    else
        owner=none
    fi
    echo "port=$port"
    echo "host=$(api_host)"
    echo "owner=$owner"
    echo "secret_set=$([ -n "$(api_secret)" ] && echo 1 || echo 0)"
}

# First free port in [start, start+99], empty when none is available.
first_free_port() {
    candidate=${1:-9090}
    limit=$((candidate + 99))
    while [ "$candidate" -le "$limit" ]; do
        if ! port_in_use "$candidate"; then
            echo "$candidate"
            return 0
        fi
        candidate=$((candidate + 1))
    done
    return 1
}

# Move the controller to a free port. Keeps the current port when it is free (or ours);
# otherwise walks 9090..9199 and rewrites config.yaml.
cmd_port_auto() { # [--no-restart]
    no_restart=$1
    ensure_secret
    port=$(api_port)
    if controller_is_ours || ! port_in_use "$port"; then
        echo "port=$port"
        echo "changed=0"
        return 0
    fi
    new_port=$(first_free_port 9090)
    if [ -z "$new_port" ]; then
        echo "error=9090-9199 全部被占用，无法自动选择端口"
        return 1
    fi
    write_controller_port "$new_port"
    write_api_json
    echo "port=$new_port"
    echo "changed=1"
    echo "old_port=$port"
    if [ "$no_restart" != "--no-restart" ] && is_running; then
        stop_core >/dev/null 2>&1
        start_core >/dev/null 2>&1
        echo "restarted=1"
    fi
    return 0
}

default_variant() {
    abi=$(getprop ro.product.cpu.abi 2>/dev/null)
    case "$abi" in
        arm64-v8a*) echo arm64-v8 ;;
        armeabi-v7a*|armeabi*) echo armv7 ;;
        x86_64*) echo amd64 ;;
        x86*) echo 386 ;;
        *) echo arm64-v8 ;;
    esac
}

conf_get() { # key
    [ -f "$CONF" ] || return 0
    grep -E "^[[:space:]]*$1[[:space:]]*:" "$CONF" 2>/dev/null | head -n 1 \
        | sed 's/^[^:]*:[[:space:]]*//' | tr -d '"' | tr -d "'" | tr -d '\r'
}

# mihomo only serves `external-ui` when the key is present *and* the directory is inside
# its working dir. Every path that rewrites config.yaml calls this so /ui (and the job
# status file the panel polls over HTTP) never turns into a 404.
ensure_ui_key() {
    [ -d "$UIHOME" ] || return 0
    [ -f "$CONF" ] || return 0
    if grep -qE '^[[:space:]]*external-ui[[:space:]]*:' "$CONF" 2>/dev/null; then
        [ "$(conf_get external-ui)" = "$UIHOME" ] || \
            sed -i "s|^[[:space:]]*external-ui[[:space:]]*:.*|external-ui: $UIHOME|" "$CONF"
    else
        printf 'external-ui: %s\n' "$UIHOME" >>"$CONF"
    fi
}

api_host() {
    value=$(conf_get external-controller)
    [ -z "$value" ] && value="127.0.0.1:9090"
    host=${value%:*}
    case "$host" in
        ""|0.0.0.0|"*"|::) host=127.0.0.1 ;;
    esac
    echo "$host"
}

api_port() {
    value=$(conf_get external-controller)
    [ -z "$value" ] && value="127.0.0.1:9090"
    port=${value##*:}
    case "$port" in
        ""|*[!0-9]*) port=9090 ;;
    esac
    echo "$port"
}

api_secret() { conf_get secret; }

# Every live mihomo process that was started from our data dir.
#
# The pid file alone is not enough: a second `start` (or a stray tool) can leave two
# processes behind, and the pid file then points at whichever wrote it last — which may
# be the one that failed to bind the controller port.
#
# `/proc/<pid>/comm` is checked first: it is a tiny file a shell `read` can slurp without
# forking, while filtering on `cmdline` + `tr` forks twice per pid (~1500 forks on a loaded
# phone, i.e. seconds per call) — and the panel calls this on every start/stop/status.
all_mihomo_pids() {
    for entry in /proc/[0-9]*; do
        comm=""
        read -r comm <"$entry/comm" 2>/dev/null || continue
        case "$comm" in
            mihomo*) ;;
            *) continue
        esac
        cmd=$(tr '\0' ' ' <"$entry/cmdline" 2>/dev/null)
        case "$cmd" in
            *"$ROOT"*) echo "${entry#/proc/}" ;;
        esac
    done
}

running_pid() {
    if [ -f "$PIDFILE" ]; then
        pid=$(cat "$PIDFILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "$pid"
            return 0
        fi
    fi
    pid=$(all_mihomo_pids | head -n 1)
    if [ -n "$pid" ]; then
        echo "$pid" >"$PIDFILE"
        echo "$pid"
        return 0
    fi
    return 1
}

is_running() { running_pid >/dev/null 2>&1; }

# Cached: reading the version means starting the (62 MB) core binary, which costs a few
# hundred milliseconds — and the panel asks for it on every state refresh. The cache is
# keyed on size+mtime, so a freshly installed kernel invalidates it automatically.
core_version() {
    [ -x "$BIN" ] || return 0
    stamp=$(stat -c '%s-%Y' "$BIN" 2>/dev/null || echo "")
    if [ -n "$stamp" ] && [ -f "$VERSIONCACHE" ] && \
        [ "$stamp" = "$(sed -n '1p' "$VERSIONCACHE" 2>/dev/null)" ]; then
        sed -n '2p' "$VERSIONCACHE"
        return 0
    fi
    version=$("$BIN" -v 2>/dev/null | head -n 1)
    [ -n "$stamp" ] && printf '%s\n%s\n' "$stamp" "$version" >"$VERSIONCACHE" 2>/dev/null
    printf '%s\n' "$version"
}

# strips the keys we always own, so a downloaded subscription can be merged safely.
# NOTE: `dns` is deliberately NOT stripped — a subscription's own dns block (fake-ip
# ranges and so on) is usually better than ours; we only fill it in when it is missing.
strip_owned_keys() { # file
    awk '
        /^[^[:space:]#]/ {
            key = $0
            sub(/:.*/, "", key)
            if (key ~ /^(external-controller|external-controller-cors|external-ui|external-ui-url|external-ui-name|secret|allow-lan|bind-address|mixed-port|port|socks-port|mode|log-level|ipv6|unified-delay|tcp-concurrent|geodata-mode|geox-url|profile|tun)$/) {
                skip = 1
                next
            } else {
                skip = 0
            }
        }
        !skip { print }
    ' "$1"
}

# prints a top-level YAML block (including its indented body) from the current config
conf_block() { # key
    [ -f "$CONF" ] || return 0
    awk -v want="$1" '
        $0 ~ "^[[:space:]]*" want "[[:space:]]*:" { flag = 1; print; next }
        flag && /^[^[:space:]]/ { flag = 0 }
        flag { print }
    ' "$CONF"
}

#---------------------------------------------------------------------
# core lifecycle
#---------------------------------------------------------------------

# Hard single-instance invariant: the module owns *exactly one* mihomo process.
#
# Concurrent starts (boot service + watchdog + WebUI button) are serialised with a lock
# directory, and a start that finds several processes — or a single one that does not own
# the controller port — kills them all and brings up one clean instance. A single healthy
# process is adopted instead of being restarted, so the watchdog stays quiet.
acquire_start_lock() {
    if mkdir "$STARTLOCK" 2>/dev/null; then
        date +%s >"$STARTLOCK/stamp" 2>/dev/null
        return 0
    fi
    # a previous start may have died while holding the lock
    age=999
    [ -f "$STARTLOCK/stamp" ] && age=$(( $(date +%s) - $(cat "$STARTLOCK/stamp" 2>/dev/null || echo 0) ))
    if [ "$age" -gt 120 ]; then
        rm -rf "$STARTLOCK" 2>/dev/null
        if mkdir "$STARTLOCK" 2>/dev/null; then
            date +%s >"$STARTLOCK/stamp" 2>/dev/null
            return 0
        fi
    fi
    return 1
}

release_start_lock() { rm -rf "$STARTLOCK" 2>/dev/null; }

start_core() {
    if ! acquire_start_lock; then
        echo "warn=另一次启动正在进行，跳过本次请求"
        return 0
    fi
    start_core_locked
    rc=$?
    release_start_lock
    return $rc
}

start_core_locked() {
    pids=$(all_mihomo_pids)
    count=$(printf '%s\n' $pids | grep -c .)

    if [ "$count" = "1" ]; then
        # adopt a healthy single instance; only "cannot verify" counts as healthy, since
        # restarting blindly would fight the watchdog
        if ! have curl || controller_is_ours; then
            echo "$pids" >"$PIDFILE"
            echo "already_running=1"
            echo "pid=$pids"
            return 0
        fi
        echo "warn=内核在运行但没有占用控制器端口，重建实例"
    elif [ "$count" -gt 1 ]; then
        echo "warn=发现 $count 个内核进程，清理后只保留一个"
    fi

    if [ "$count" -ge 1 ]; then
        kill_all_core >/dev/null 2>&1
        # mihomo's graceful shutdown keeps its sockets for several seconds (it waits on the
        # cache file and closes connections). Starting inside that window makes the new
        # instance fail to bind the controller / mixed-port / DNS, so wait — but poll
        # instead of sleeping a fixed amount, the sockets usually come back in <1s.
        wait_port=$(api_port)
        i=0
        while [ $i -lt 100 ]; do
            if [ -z "$(all_mihomo_pids | head -n 1)" ] && ! port_in_use "$wait_port"; then
                break
            fi
            sleep 0.2
            i=$((i + 1))
        done
    fi

    if [ ! -x "$BIN" ]; then
        echo "error=内核二进制不存在或不可执行：$BIN"
        return 1
    fi
    if [ ! -f "$CONF" ]; then
        echo "error=配置文件不存在：$CONF"
        return 1
    fi
    # Another proxy may already own the configured controller port; move before binding
    # so the UI never ends up talking to someone else's instance.
    ensure_secret
    ctl_port=$(api_port)
    if port_in_use "$ctl_port" && ! controller_is_ours; then
        free_port=$(first_free_port 9090)
        if [ -n "$free_port" ]; then
            write_controller_port "$free_port"
            echo "warn=控制端口 $ctl_port 被其它代理占用，已自动切换到 $free_port"
        fi
    fi
    # Pre-flight check, skipped when this exact config file already passed once: starting
    # the 62 MB binary for `-t` costs ~0.5s and restart is a hot path for the panel.
    # It is only a warning anyway: on a fresh install the first run has to download
    # GeoIP.dat, so a network hiccup would otherwise block the start for good.
    conf_stamp=$(stat -c '%s-%Y' "$CONF" 2>/dev/null || echo "")
    if [ -n "$conf_stamp" ] && [ "$conf_stamp" = "$(cat "$CONFCHECK" 2>/dev/null)" ]; then
        :
    else
        check=$("$BIN" -t -d "$GEO" -f "$CONF" 2>&1)
        if [ $? -ne 0 ]; then
            echo "warn=配置校验未通过，仍尝试启动"
            echo "$check" | tail -n 4
        else
            [ -n "$conf_stamp" ] && printf '%s' "$conf_stamp" >"$CONFCHECK" 2>/dev/null
        fi
    fi
    : >>"$LOG"
    echo "----- start $(date '+%F %T') -----" >>"$LOG"
    if have nohup; then
        nohup "$BIN" -d "$GEO" -f "$CONF" >>"$LOG" 2>&1 &
    else
        "$BIN" -d "$GEO" -f "$CONF" >>"$LOG" 2>&1 &
    fi
    pid=$!
    echo "$pid" >"$PIDFILE"
    # Wait until the controller actually answers instead of sleeping a fixed amount: the
    # API is normally up in well under a second, and the panel is blocked on this call.
    api_host_v=$(api_host)
    api_port_v=$(api_port)
    api_secret_v=$(api_secret)
    version_url="http://$api_host_v:$api_port_v/version"
    i=0
    ready=0
    while [ $i -lt 100 ]; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "error=内核进程意外退出，请查看日志"
            tail -n 10 "$LOG" 2>/dev/null
            rm -f "$PIDFILE"
            return 1
        fi
        if have curl && curl -s -m 1 -H "Authorization: Bearer $api_secret_v" \
            "$version_url" 2>/dev/null | grep -q '"version"'; then
            ready=1
            break
        fi
        sleep 0.2
        i=$((i + 1))
    done
    # kill anything that slipped in during the wait (the watchdog may have raced us)
    for other in $(all_mihomo_pids); do
        [ "$other" = "$pid" ] && continue
        echo "warn=清理并发启动的重复进程 $other"
        kill -TERM "$other" 2>/dev/null
    done
    echo "started=1"
    echo "pid=$pid"
    echo "version=$(core_version)"
    write_api_json
    if [ "$ready" = "0" ]; then
        echo "warn=控制端口 $(api_host):$(api_port) 未在 20 秒内就绪，界面可能连不上"
    fi
    return 0
}

# Kills every mihomo process belonging to the module. Deliberately does *not* touch the
# start lock: stop_core() may be called from inside a locked start.
kill_all_core() {
    pids=$(all_mihomo_pids)
    pid=$(running_pid 2>/dev/null)
    if [ -n "$pid" ]; then
        pids="$pids $pid"
    fi
    pids=$(printf '%s\n' $pids | sort -u | tr '\n' ' ')
    if [ -z "$(printf '%s' "$pids" | tr -d ' ')" ]; then
        rm -f "$PIDFILE"
        return 1
    fi
    for p in $pids; do
        kill -TERM "$p" 2>/dev/null
    done
    i=0
    # Graceful shutdown first, but only briefly: mihomo can take ~18s to let go (it waits
    # on its cache file) and the panel is blocked on this call, so after ~2.4s we force it.
    i=0
    while [ $i -lt 12 ]; do
        alive=0
        for p in $pids; do
            kill -0 "$p" 2>/dev/null && alive=1
        done
        [ "$alive" = 0 ] && break
        sleep 0.2
        i=$((i + 1))
    done
    for p in $pids; do
        if kill -0 "$p" 2>/dev/null; then
            kill -9 "$p" 2>/dev/null
        fi
    done
    rm -f "$PIDFILE"
    return 0
}

stop_core() {
    if kill_all_core; then
        echo "stopped=1"
    else
        echo "running=0"
    fi
    release_start_lock
    return 0
}

status_core() {
    if is_running; then
        echo "running=1"
        echo "pid=$(running_pid 2>/dev/null)"
    else
        echo "running=0"
    fi
    echo "version=$(core_version)"
    echo "config=$CONF"
    echo "log=$LOG"
    echo "api=$(api_host):$(api_port)"
}

#---------------------------------------------------------------------
# information
#---------------------------------------------------------------------

cmd_api_info() {
    write_api_json
    echo "host=$(api_host)"
    echo "port=$(api_port)"
    echo "secret=$(api_secret)"
}

# Mirror the controller coordinates into the WebUI directory.
#
# The panel can then read them with a same-origin `fetch('api.json')`, which works in a
# plain browser too (mihomo serves `external-ui` at /ui) — no KernelSU bridge needed just
# to talk to the API. Keep in mind the file is readable by anything that can reach the
# controller port, exactly like the API itself.
# `api_host` / `api_port` / `api_secret` each re-read the config; passing them in keeps
# `state` (the panel's startup call) down to a single pass.
write_api_json_values() { # host port secret
    for dir in "$WEBROOT" "$UIHOME"; do
        [ -d "$dir" ] || continue
        {
            printf '{\n'
            printf '  "host": "%s",\n' "$1"
            printf '  "port": %s,\n' "$2"
            printf '  "secret": "%s",\n' "$3"
            printf '  "module": "mkclash",\n'
            printf '  "updated": "%s"\n' "$(date '+%F %T')"
            printf '}\n'
        } >"$dir/api.json" 2>/dev/null
        chmod 644 "$dir/api.json" 2>/dev/null
    done
}

write_api_json() {
    write_api_json_values "$(api_host)" "$(api_port)" "$(api_secret)"
}

cmd_core_info() {
    echo "path=$BIN"
    echo "version=$(core_version)"
    if [ -x "$BIN" ]; then
        size=$(du -h "$BIN" 2>/dev/null | awk '{print $1}')
        echo "size=${size:--}"
    else
        echo "size=-"
    fi
    echo "arch=$(getprop ro.product.cpu.abi 2>/dev/null)"
    echo "variant=$(default_variant)"
    echo "running=$(is_running && echo 1 || echo 0)"
}

cmd_logs() {
    count=${1:-200}
    [ -f "$LOG" ] || { echo ""; return 0; }
    tail -n "$count" "$LOG"
}

#---------------------------------------------------------------------
# configuration
#---------------------------------------------------------------------

cmd_config_install() {
    if [ ! -f "$STAGED" ]; then
        echo "error=没有待安装的配置（$STAGED）"
        return 1
    fi
    if [ ! -x "$BIN" ]; then
        echo "error=内核未安装，无法校验配置"
        return 1
    fi
    out=$("$BIN" -t -d "$GEO" -f "$STAGED" 2>&1)
    if [ $? -ne 0 ]; then
        echo "error=配置校验失败"
        echo "$out" | tail -n 8
        return 1
    fi
    was_running=0
    is_running && was_running=1
    [ -f "$CONF" ] && cp -f "$CONF" "$CONF.bak"
    mv -f "$STAGED" "$CONF"
    chmod 600 "$CONF"
    echo "installed=1"
    echo "backup=$CONF.bak"
    ensure_ui_key
    echo "was_running=$was_running"
    if [ "$was_running" = 0 ]; then
        start_core >/dev/null 2>&1
    fi
    return 0
}

cmd_config_show() {
    [ -f "$CONF" ] || { echo "error=配置文件不存在"; return 1; }
    cat "$CONF"
}

#---------------------------------------------------------------------
# subscriptions
#---------------------------------------------------------------------

sub_normalise() { # file -> file with our overrides applied
    file=$1
    tmp=$RUN/sub.clean.yaml
    strip_owned_keys "$file" >"$tmp"
    # keep the subscription's own dns block; fall back to the one in use
    if ! grep -qE '^[[:space:]]*dns[[:space:]]*:' "$tmp"; then
        echo "$dns_block" >>"$tmp"
    fi
    {
        echo ""
        echo "# ---- managed by mihomo module WebUI ----"
        if [ -n "$(api_secret)" ]; then
            echo "secret: $(api_secret)"
        fi
        echo "external-controller: $(api_host):$(api_port)"
        echo "external-controller-cors:"
        echo "  allow-origins:"
        echo "    - '*'"
        echo "  allow-private-network: true"
        # the panel is served by the core itself; a subscription that overwrites the config
        # must not take this away, or /ui and the job status file turn into 404s
        [ -d "$UIHOME" ] && echo "external-ui: $UIHOME"
        echo "mixed-port: $mixed_port"
        echo "allow-lan: $allow_lan"
        # '*' must be quoted or YAML reads it as an alias
        echo "bind-address: '$bind_address'"
        echo "mode: $mode_value"
        echo "log-level: $log_level"
        echo "ipv6: $ipv6_value"
        echo "unified-delay: true"
        echo "tcp-concurrent: true"
        echo "geodata-mode: true"
        echo "geox-url:"
        echo "  geoip: \"https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.dat\""
        echo "  geosite: \"https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat\""
        echo "  mmdb: \"https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/country.mmdb\""
        echo "profile:"
        echo "  store-selected: true"
        echo "  store-fake-ip: true"
        [ -n "$tun_block" ] && echo "$tun_block"
    } >>"$tmp"
    echo "$tmp"
}

sub_update_one() { # name url
    name=$1
    url=$2
    # tolerate being called directly (not through cmd_sub_update)
    [ -z "$mixed_port" ] && mixed_port=7890
    [ -z "$allow_lan" ] && allow_lan=false
    [ -z "$bind_address" ] && bind_address='*'
    [ -z "$mode_value" ] && mode_value=rule
    [ -z "$log_level" ] && log_level=info
    [ -z "$ipv6_value" ] && ipv6_value=false
    [ -z "$tun_block" ] && tun_block=$(conf_block tun)
    [ -z "$dns_block" ] && dns_block=$(conf_block dns)
    [ -z "$url" ] && { echo "error=订阅 $name 没有 URL"; return 1; }
    raw=$RUN/sub.download.yaml
    if ! download_sub "$url" "$raw"; then
        echo "error=下载失败：$name（当前 UA：$(sub_ua)）"
        return 1
    fi
    if [ ! -s "$raw" ]; then
        echo "error=下载内容为空：$name"
        return 1
    fi
    case "$(head -c 400 "$raw")" in
        *"<html"*|*"<!DOCTYPE"*|*"<!doctype"*)
            echo "error=订阅返回了网页而不是配置（可换 UA：当前 $(sub_ua)）"
            return 1
            ;;
    esac
    # v2ray/ss 的 base64 节点列表没有 YAML 的 "key:" 字段，直接丢给 mihomo 只会报语法错
    if ! grep -q ':' "$raw" 2>/dev/null; then
        echo "error=订阅返回的是 base64 节点列表，不是 Clash 配置：请把 UA 改成机场支持的客户端" +
            "（当前 $(sub_ua)，可用 sub-ua 命令修改）"
        return 1
    fi
    merged=$(sub_normalise "$raw")
    out=$("$BIN" -t -d "$GEO" -f "$merged" 2>&1)
    if [ $? -ne 0 ]; then
        echo "error=订阅配置校验失败：$name"
        echo "$out" | tail -n 6
        return 1
    fi
    was_running=0
    is_running && was_running=1
    [ -f "$CONF" ] && cp -f "$CONF" "$CONF.bak"
    cp -f "$merged" "$CONF"
    chmod 600 "$CONF"
    nodes=$(awk '/^[[:space:]]*-[[:space:]]*\{?[[:space:]]*name:/{c++} END{print c+0}' "$CONF")
    stamp=$(date '+%Y-%m-%d %H:%M')
    tmp_subs=$SUBS.tmp
    awk -v n="$name" -v s="$stamp" -v c="$nodes" -F'\t' 'BEGIN{OFS="\t"}
        $1==n { print n, $2, s, c; next }
        { print }
    ' "$SUBS" >"$tmp_subs" 2>/dev/null
    [ -s "$tmp_subs" ] && mv -f "$tmp_subs" "$SUBS"
    echo "updated=$name"
    echo "node_count=$nodes"
    echo "config=$CONF"
    if [ "$was_running" = 1 ]; then
        stop_core >/dev/null 2>&1
        start_core >/dev/null 2>&1
        echo "restarted=1"
    fi
    return 0
}

cmd_sub_list() {
    [ -f "$SUBS" ] || return 0
    cat "$SUBS"
}

# Read or write the subscription User-Agent (`sub-ua` / `sub-ua <value>`).
cmd_sub_ua() {
    value=$(printf '%s' "$1" | tr -d '\r\n')
    if [ -n "$value" ]; then
        printf '%s\n' "$value" >"$UA_FILE" 2>/dev/null
        chmod 644 "$UA_FILE" 2>/dev/null
    fi
    echo "ua=$(sub_ua)"
}

# Everything the panel needs at startup in one round-trip: connection coordinates,
# autostart flag, controller ownership, version, subscription UA.
#
# Batching matters: on the KernelSU host every `exec` blocks the WebView's JS thread, so
# three separate calls are three visible freezes.
cmd_state() {
    host=$(api_host)
    port=$(api_port)
    secret=$(api_secret)
    write_api_json_values "$host" "$port" "$secret"
    echo "host=$host"
    echo "port=$port"
    echo "secret=$secret"
    if [ -f "$AUTOSTART" ]; then echo "autostart=1"; else echo "autostart=0"; fi
    if controller_is_ours; then
        echo "owner=ours"
    elif port_in_use "$port"; then
        echo "owner=other"
    else
        echo "owner=none"
    fi
    if is_running; then echo "running=1"; else echo "running=0"; fi
    echo "version=$(core_version)"
    echo "ua=$(sub_ua)"
}

cmd_sub_add() {
    name=$1
    url=$2
    [ -z "$name" ] && { echo "error=订阅名称不能为空"; return 1; }
    [ -z "$url" ] && { echo "error=订阅链接不能为空"; return 1; }
    case "$url" in
        http://*|https://*) ;;
        *) echo "error=订阅链接必须以 http:// 或 https:// 开头"; return 1 ;;
    esac
    tmp_subs=$SUBS.tmp
    if [ -f "$SUBS" ]; then
        awk -v n="$name" -F'\t' '$1!=n' "$SUBS" >"$tmp_subs"
    else
        : >"$tmp_subs"
    fi
    printf '%s\t%s\t%s\t%s\n' "$name" "$url" "未更新" "0" >>"$tmp_subs"
    mv -f "$tmp_subs" "$SUBS"
    echo "added=$name"
}

cmd_sub_remove() {
    name=$1
    [ -f "$SUBS" ] || { echo "error=订阅列表为空"; return 1; }
    tmp_subs=$SUBS.tmp
    awk -v n="$name" -F'\t' '$1!=n' "$SUBS" >"$tmp_subs"
    mv -f "$tmp_subs" "$SUBS"
    echo "removed=$name"
}

cmd_sub_update() {
    name=$1
    [ -f "$SUBS" ] || { echo "error=订阅列表为空"; return 1; }
    [ -x "$BIN" ] || { echo "error=内核未安装，无法校验配置"; return 1; }
    # cache the current settings so the merged file keeps them
    mixed_port=$(conf_get mixed-port)
    [ -z "$mixed_port" ] && mixed_port=7890
    allow_lan=$(conf_get allow-lan)
    [ -z "$allow_lan" ] && allow_lan=false
    bind_address=$(conf_get bind-address)
    [ -z "$bind_address" ] && bind_address='*'
    mode_value=$(conf_get mode)
    [ -z "$mode_value" ] && mode_value=rule
    log_level=$(conf_get log-level)
    [ -z "$log_level" ] && log_level=info
    ipv6_value=$(conf_get ipv6)
    [ -z "$ipv6_value" ] && ipv6_value=false
    tun_block=$(conf_block tun)
    dns_block=$(conf_block dns)

    failed=0
    if [ "$name" = "all" ]; then
        while IFS='	' read -r sub_name sub_url _rest; do
            [ -z "$sub_name" ] && continue
            sub_update_one "$sub_name" "$sub_url" || failed=$((failed + 1))
        done <"$SUBS"
    else
        line=$(awk -v n="$name" -F'\t' '$1==n' "$SUBS" | head -n 1)
        if [ -z "$line" ]; then
            echo "error=找不到订阅：$name"
            return 1
        fi
        sub_url=$(printf '%s' "$line" | cut -f2)
        sub_update_one "$name" "$sub_url" || return 1
    fi
    ensure_ui_key
    [ "$failed" -gt 0 ] && echo "failed=$failed"
    return 0
}

#---------------------------------------------------------------------
# core update
#---------------------------------------------------------------------

# `mirror` is a user supplied accelerator prefix ("https://gh-proxy.com/").
# Normalise it so `<prefix><url>` always forms a valid URL.
normalise_mirror() {
    value=$(printf '%s' "$1" | tr -d ' \r\n\t')
    case "$value" in
        ""|-|none) echo ""; return 0 ;;
    esac
    case "$value" in
        *://*) ;;
        *) value="https://$value" ;;
    esac
    case "$value" in
        */) ;;
        *) value="$value/" ;;
    esac
    echo "$value"
}

# Turn whatever a source returned into a canonical "vX.Y.Z" tag.
normalise_tag() {
    value=$(printf '%s' "$1" | tr -d ' \r\n\t"')
    [ -z "$value" ] && return 1
    version=$(printf '%s' "$value" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)
    [ -z "$version" ] && return 1
    echo "v$version"
}

# Resolve the newest mihomo release tag.
#
# api.github.com is blocked or rate limited on a lot of networks (it simply times out
# in mainland China), so four independent sources are tried in order. `source=` is
# printed to stderr-friendly stdout so the UI can show which one answered.
resolve_latest() { # mirror
    MIRROR_PREFIX=$1
    tag=""
    source=""

    # 1) official REST API (best metadata, needs api.github.com)
    json=$(fetch_github "https://api.github.com/repos/MetaCubeX/mihomo/releases/latest") || json=""
    if [ -n "$json" ]; then
        raw=$(printf '%s' "$json" | grep -o '"tag_name"[^,]*' | head -n 1 | sed 's/.*"\(v[^"]*\)".*/\1/')
        tag=$(normalise_tag "$raw" 2>/dev/null) || tag=""
        [ -n "$tag" ] && source="github-api"
    fi

    # 2) the /releases/latest redirect — no API, just github.com
    if [ -z "$tag" ]; then
        location=$(head_github "https://github.com/MetaCubeX/mihomo/releases/latest")
        case "$location" in
            */tag/*)
                tag=$(normalise_tag "${location##*/tag/}" 2>/dev/null) || tag=""
                [ -n "$tag" ] && source="github-redirect"
                ;;
        esac
    fi

    # 3) jsDelivr data API — reachable where GitHub is not
    if [ -z "$tag" ]; then
        json=$(fetch_github "https://data.jsdelivr.com/v1/packages/gh/MetaCubeX/mihomo/resolved") || json=""
        if [ -n "$json" ]; then
            raw=$(printf '%s' "$json" | grep -o '"version"[^,]*' | head -n 1 | sed 's/.*:[[:space:]]*"\([^"]*\)".*/\1/')
            tag=$(normalise_tag "$raw" 2>/dev/null) || tag=""
            [ -n "$tag" ] && source="jsdelivr"
        fi
    fi

    # 4) the release atom feed — plain XML, last resort
    if [ -z "$tag" ]; then
        xml=$(fetch_github "https://github.com/MetaCubeX/mihomo/releases.atom") || xml=""
        if [ -n "$xml" ]; then
            # first <title> that looks like a version (the feed also carries
            # "Release notes from mihomo" and "Prerelease-Alpha" entries)
            raw=$(printf '%s' "$xml" | grep -o '<title>[^<]*</title>' \
                | sed 's#</\?title>##g' | grep -E '^v?[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)
            tag=$(normalise_tag "$raw" 2>/dev/null) || tag=""
            [ -n "$tag" ] && source="atom"
        fi
    fi

    if [ -z "$tag" ]; then
        echo "error=无法获取最新版本号（api.github.com / github.com 可能被墙，可填写加速前缀或手动指定版本）"
        return 1
    fi
    # callers use command substitution (a subshell), so the source travels alongside
    # the tag instead of through a global variable
    echo "$tag $source"
}

cmd_core_check() {
    mirror=$(normalise_mirror "$1")
    variant=$2
    [ -z "$variant" ] && variant=$(default_variant)
    latest=$(resolve_latest "$mirror") || { echo "$latest"; return 1; }
    resolved_source=$(printf '%s' "$latest" | awk '{print $2}')
    latest=$(printf '%s' "$latest" | awk '{print $1}')
    # `mihomo -v` prints e.g. "Mihomo Meta v1.19.32 linux arm64 with go1.26.8 ..."
    current=$(core_version | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)
    asset="mihomo-android-$variant-$latest.gz"
    echo "current=$current"
    echo "latest=$latest"
    echo "asset=$asset"
    echo "variant=$variant"
    echo "mirror=$mirror"
    echo "source=${resolved_source:-unknown}"
    echo "url=https://github.com/MetaCubeX/mihomo/releases/download/$latest/$asset"
    echo "fallback_mirrors=$BUILTIN_MIRRORS"
    if [ "$current" = "$latest" ]; then
        echo "up_to_date=1"
    else
        echo "up_to_date=0"
    fi
    for dir in "$UIHOME" "$WEBROOT"; do
        [ -d "$dir" ] || continue
        {
            printf '{\n'
            printf '  "current": "%s",\n' "$current"
            printf '  "latest": "%s",\n' "$latest"
            printf '  "asset": "%s",\n' "$asset"
            printf '  "variant": "%s",\n' "$variant"
            printf '  "mirror": "%s",\n' "$mirror"
            printf '  "source": "%s",\n' "${resolved_source:-unknown}"
            printf '  "url": "%s",\n' "https://github.com/MetaCubeX/mihomo/releases/download/$latest/$asset"
            printf '  "up_to_date": %s\n' "${up_to_date:-0}"
            printf '}\n'
        } >"$dir/core-check.json" 2>/dev/null
        chmod 644 "$dir/core-check.json" 2>/dev/null
    done
    return 0
}

cmd_core_update() {
    version=$(printf '%s' "$1" | tr -d ' \r\n\t')
    variant=$2
    mirror=$(normalise_mirror "$3")
    [ -z "$variant" ] && variant=$(default_variant)
    case "$version" in
        ""|latest) version="" ;;
        v[0-9]*) ;;
        [0-9]*) version="v$version" ;;
    esac
    if [ -z "$version" ] || [ "$version" = "latest" ]; then
        resolved=$(resolve_latest "$mirror") || { echo "$resolved"; return 1; }
        version=$(printf '%s' "$resolved" | awk '{print $1}')
        echo "resolved_from=$(printf '%s' "$resolved" | awk '{print $2}')"
    fi
    asset="mihomo-android-$variant-$version.gz"
    path="https://github.com/MetaCubeX/mihomo/releases/download/$version/$asset"
    gz=$RUN/mihomo.gz
    new=$RUN/mihomo.new
    rm -f "$gz" "$new"
    MIRROR_PREFIX=$mirror
    if ! download_github "$path" "$gz"; then
        return 1
    fi
    if ! gunzip -c "$gz" >"$new" 2>/dev/null; then
        echo "error=解压失败（下载内容可能不是有效的 gzip）"
        rm -f "$gz"
        return 1
    fi
    chmod 755 "$new"
    new_version=$("$new" -v 2>&1 | head -n 1)
    # the binary must actually execute and look like a mihomo release before we touch
    # the installed one (matching on the output alone would also accept error text)
    if ! "$new" -v >/dev/null 2>&1; then
        echo "error=新内核无法运行：$new_version"
        rm -f "$new" "$gz"
        return 1
    fi
    case "$new_version" in
        *[0-9].[0-9]*) ;;
        *)
            echo "error=新内核版本信息异常：$new_version"
            rm -f "$new" "$gz"
            return 1
            ;;
    esac
    was_running=0
    is_running && was_running=1
    [ "$was_running" = 1 ] && stop_core >/dev/null 2>&1
    if [ -f "$BIN" ]; then
        cp -f "$BIN" "$BIN.bak"
        echo "backup=$BIN.bak"
    fi
    mv -f "$new" "$BIN"
    chmod 755 "$BIN"
    rm -f "$gz"
    echo "installed=$new_version"
    if [ "$was_running" = 1 ]; then
        if start_core >/dev/null 2>&1; then
            echo "restarted=1"
        else
            echo "restarted=0"
            echo "error=新内核启动失败，正在回滚"
            if [ -f "$BIN.bak" ]; then
                cp -f "$BIN.bak" "$BIN"
                chmod 755 "$BIN"
                start_core >/dev/null 2>&1
                echo "rolled_back=1"
            fi
        fi
    fi
    return 0
}

#---------------------------------------------------------------------
# autostart + misc
#---------------------------------------------------------------------

cmd_autostart() {
    case "$1" in
        on|1|true)
            touch "$AUTOSTART"
            echo "autostart=1"
            ;;
        off|0|false)
            rm -f "$AUTOSTART"
            echo "autostart=0"
            ;;
        *)
            [ -f "$AUTOSTART" ] && echo "autostart=1" || echo "autostart=0"
            ;;
    esac
}

cmd_geo_update() {
    mirror=$(normalise_mirror "$1")
    MIRROR_PREFIX=$mirror
    for item in "geoip.dat https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.dat" \
        "geosite.dat https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat" \
        "country.mmdb https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/country.mmdb"; do
        name=${item%% *}
        url=${item##* }
        echo "fetching=$name"
        download_to "${mirror}${url}" "$ROOT/$name" || echo "error=$name 下载失败"
    done
    echo "done=1"
}

usage() {
    cat <<EOF
mihomo.sh <command> [args]

  core start|stop|restart|status|toggle
  api-info
  core-info
  logs [lines]
  config-show
  config-install
  sub-list | sub-add <name> <url> | sub-remove <name> | sub-update <name|all>
  sub-ua [value]           # 订阅 UA（默认 clash-verge/v1.6.0）
  state                    # 一次性返回连接坐标 / 自启 / 端口占用 / 版本 / UA
  job <kind> [args]        # 后台任务（sub-update/core-restart/config-install/...），立即返回
  job clear                # 清除已完成的任务状态
  core-check [mirror] [variant]
  resolve-latest [mirror]
  core-update <version|latest> [variant] [mirror]
  autostart on|off
  port-status              # port= / host= / owner=ours|other|none / secret_set=
  port-auto [--no-restart] # 端口被别的代理占用时自动换到空闲端口
  geo-update [mirror]
EOF
}


#---------------------------------------------------------------------
# background jobs
#
# Every `ksu.exec` blocks the WebView's JS thread on the host side, so a long operation
# (downloading a subscription, replacing the kernel, validating a config) must never run
# inside that call: the whole panel would freeze for its duration. `job <kind> [args]`
# spawns the work detached, writes a status file the panel reads over plain HTTP
# (`external-ui` serves $UIHOME, so it is a static file fetch — no exec, no blocking) and
# returns immediately.
#---------------------------------------------------------------------

json_escape() {
    printf '%s' "$1" \
        | sed 's/\\/\\\\/g; s/"/\\"/g' \
        | tr '\n\r\t' '   ' \
        | cut -c1-400
}

job_write() { # id kind state message [started]
    started=${5:-$(date +%s)}
    [ -z "$started" ] && started=$(date +%s)
    for dir in "$UIHOME" "$WEBROOT"; do
        [ -d "$dir" ] || continue
        {
            printf '{\n'
            printf '  "id": "%s",\n' "$1"
            printf '  "kind": "%s",\n' "$2"
            printf '  "state": "%s",\n' "$3"
            printf '  "message": "%s",\n' "$(json_escape "$4")"
            printf '  "started": %s,\n' "$started"
            printf '  "updated": %s\n' "$(date +%s)"
            printf '}\n'
        } >"$dir/job.json" 2>/dev/null
        chmod 644 "$dir/job.json" 2>/dev/null
    done
}

# `job clear` lets the panel dismiss a finished job.
job_clear() { job_write "" "" "idle" "" ""; }

cmd_job() { # kind [args...]
    kind=$1
    shift 2>/dev/null
    case "$kind" in
        sub-update | sub-fetch | sub-add | sub-remove | core-start | core-stop | core-restart | core-check | \
        config-install | core-update | geo-update | port-auto) ;;
        clear) job_clear; echo "state=idle"; return 0 ;;
        *) echo "error=未知任务：$kind"; return 1 ;;
    esac
    id="$(date +%s)-$$"
    mkdir -p "$JOBDIR" 2>/dev/null
    started=$(date +%s)
    job_write "$id" "$kind" running "任务已开始" "$started"
    quoted=""
    for a in "$@"; do
        quoted="$quoted '$(printf '%s' "$a" | sed "s/'/'\\\\''/g")'"
    done
    if have nohup; then
        nohup sh -c "sh '$SELF' job-run '$id' '$kind'$quoted >>'$JOBDIR/$id.log' 2>&1" >/dev/null 2>&1 &
    else
        sh -c "sh '$SELF' job-run '$id' '$kind'$quoted >>'$JOBDIR/$id.log' 2>&1" >/dev/null 2>&1 &
    fi
    echo "job=$id"
    echo "kind=$kind"
    echo "state=running"
}

cmd_job_run() { # id kind [args...]
    id=$1
    kind=$2
    shift 2 2>/dev/null
    started=$(date +%s)
    case "$kind" in
        sub-update) out=$(cmd_sub_update "$1") ;;
        sub-fetch) out=$(cmd_sub_fetch "$1") ;;
        core-check) out=$(cmd_core_check "$1" "$2") ;;
        sub-add) out=$(cmd_sub_add "$1" "$2") ;;
        sub-remove) out=$(cmd_sub_remove "$1") ;;
        core-start) out=$(start_core) ;;
        core-stop) out=$(stop_core) ;;
        core-restart) out=$(stop_core >/dev/null 2>&1; start_core) ;;
        config-install) out=$(cmd_config_install) ;;
        core-update) out=$(cmd_core_update "$1" "$2" "$3") ;;
        geo-update) out=$(cmd_geo_update "$1") ;;
        port-auto) out=$(cmd_port_auto "$1") ;;
        *) out="error=未知任务：$kind" ;;
    esac
    rc=$?
    if [ "$rc" -eq 0 ] && ! printf '%s\n' "$out" | grep -q '^error='; then
        state=done
    else
        state=failed
    fi
    msg=$(printf '%s\n' "$out" \
        | grep -E '^(error|warn|updated|added|removed|started|stopped|restarted|node_count|port|version|config)=' \
        | tail -n 3 | tr '\n' ' ')
    [ -z "$msg" ] && msg=$(printf '%s\n' "$out" | tail -n 2 | tr '\n' ' ')
    [ -z "$msg" ] && msg="任务结束"
    # keep the raw output around for the "details" view in the panel
    printf '%s\n' "$out" >"$JOBDIR/$id.out" 2>/dev/null
    job_write "$id" "$kind" "$state" "$msg" "$started"
    # a finished job may have rewritten the controller port / config
    write_api_json 2>/dev/null
}
#---------------------------------------------------------------------
# dispatch
#---------------------------------------------------------------------

cmd=$1
shift 2>/dev/null

case "$cmd" in
    core)
        case "$1" in
            start) start_core ;;
            stop) stop_core ;;
            restart) stop_core >/dev/null 2>&1; start_core ;;
            status) status_core ;;
            toggle) if is_running; then stop_core; else start_core; fi ;;
            *) usage; exit 1 ;;
        esac
        ;;
    job) cmd_job "$@" ;;
    log-tail) cmd_log_tail "$1" ;;
    log-tail-loop) cmd_log_tail_loop ;;
    job-run) cmd_job_run "$@" ;;
    api-info) cmd_api_info ;;
    core-info) cmd_core_info ;;
    logs) cmd_logs "$1" ;;
    config-show) cmd_config_show ;;
    config-install) cmd_config_install ;;
    sub-list) cmd_sub_list ;;
    sub-ua) cmd_sub_ua "$1" ;;
    state) cmd_state ;;
    sub-add) cmd_sub_add "$1" "$2" ;;
    sub-remove) cmd_sub_remove "$1" ;;
    sub-update) cmd_sub_update "$1" ;;
    sub-fetch) cmd_sub_fetch "$1" ;;
    core-check) cmd_core_check "$1" "$2" ;;
    resolve-latest)
        mirror=$(normalise_mirror "$1")
        resolved=$(resolve_latest "$mirror") || { echo "$resolved"; exit 1; }
        echo "latest=$(printf '%s' "$resolved" | awk '{print $1}')"
        echo "source=$(printf '%s' "$resolved" | awk '{print $2}')"
        echo "mirror=$mirror"
        ;;
    core-update) cmd_core_update "$1" "$2" "$3" ;;
    autostart) cmd_autostart "$1" ;;
    port-status) cmd_port_status ;;
    port-auto) cmd_port_auto "$1" ;;
    geo-update) cmd_geo_update "$1" ;;
    "" | help | -h | --help) usage ;;
    *) echo "error=未知命令：$cmd"; usage; exit 1 ;;
esac

#---------------------------------------------------------------------
# log tail
#
# The log viewer used to run `tail` through the (blocking) exec bridge every few seconds.
# Instead a detached loop refreshes a file inside the served UI directory and the panel
# fetches that — same trick as the job status, so watching logs costs no exec at all.
#---------------------------------------------------------------------

cmd_log_tail() { # start|stop
    case "$1" in
        start)
            rm -f "$RUN/log-tail.stop" 2>/dev/null
            if have nohup; then
                nohup sh -c "sh '$SELF' log-tail-loop" >/dev/null 2>&1 &
            else
                sh -c "sh '$SELF' log-tail-loop" >/dev/null 2>&1 &
            fi
            echo "tail=started"
            ;;
        stop)
            : >"$RUN/log-tail.stop" 2>/dev/null
            echo "tail=stopped"
            ;;
        *)
            echo "error=用法：log-tail start|stop"
            return 1
            ;;
    esac
}

cmd_log_tail_loop() {
    mkdir "$RUN/.logtail.lock" 2>/dev/null || return 0
    i=0
    while [ $i -lt 300 ]; do
        [ -f "$RUN/log-tail.stop" ] && break
        if [ -f "$LOG" ]; then
            tail -n 300 "$LOG" >"$UIHOME/log.txt" 2>/dev/null
            [ -d "$WEBROOT" ] && cp -f "$UIHOME/log.txt" "$WEBROOT/log.txt" 2>/dev/null
            chmod 644 "$UIHOME/log.txt" 2>/dev/null
        fi
        sleep 2
        i=$((i + 1))
    done
    rm -f "$RUN/log-tail.stop" 2>/dev/null
    rmdir "$RUN/.logtail.lock" 2>/dev/null
}

# Downloads a subscription, merges our overrides and drops the result into the *served* UI
# directory as `pending.yaml` — without touching config.yaml or restarting the core.
#
# The panel then hands that YAML to the core itself (`PUT /configs` with a payload), which
# parses and applies it live, and only then persists the file. Result: updating a
# subscription no longer drops every connection, and the validation error comes from the
# core instead of a `mihomo -t` round trip.
cmd_sub_fetch() { # name
    name=$1
    [ -z "$name" ] && { echo "error=缺少订阅名"; return 1; }
    line=$(awk -v n="$name" -F'\t' '$1==n' "$SUBS" | head -n 1)
    [ -z "$line" ] && { echo "error=找不到订阅：$name"; return 1; }
    url=$(printf '%s' "$line" | cut -f2)
    [ -z "$url" ] && { echo "error=订阅 $name 没有 URL"; return 1; }
    mixed_port=${mixed_port:-7890}
    [ -z "$mixed_port" ] && mixed_port=7890
    allow_lan=${allow_lan:-false}
    [ -z "$allow_lan" ] && allow_lan=false
    bind_address=${bind_address:-'*'}
    [ -z "$bind_address" ] && bind_address='*'
    mode_value=${mode_value:-rule}
    [ -z "$mode_value" ] && mode_value=rule
    log_level=${log_level:-info}
    [ -z "$log_level" ] && log_level=info
    ipv6_value=${ipv6_value:-false}
    [ -z "$ipv6_value" ] && ipv6_value=false
    [ -z "$tun_block" ] && tun_block=$(conf_block tun)
    [ -z "$dns_block" ] && dns_block=$(conf_block dns)
    raw=$RUN/sub.download.yaml
    if ! download_sub "$url" "$raw"; then
        echo "error=下载失败：$name（当前 UA：$(sub_ua)）"
        return 1
    fi
    if [ ! -s "$raw" ]; then
        echo "error=下载内容为空：$name"
        return 1
    fi
    case "$(head -c 400 "$raw")" in
        *"<html"*|*"<!DOCTYPE"*|*"<!doctype"*)
            echo "error=订阅返回了网页而不是配置（当前 UA：$(sub_ua)）"
            return 1
            ;;
    esac
    if ! grep -q ':' "$raw" 2>/dev/null; then
        echo "error=订阅返回的是 base64 节点列表，不是 Clash 配置（当前 UA：$(sub_ua)）"
        return 1
    fi
    merged=$(sub_normalise "$raw")
    nodes=$(awk '/^[[:space:]]*-[[:space:]]*\{?[[:space:]]*name:/{c++} END{print c+0}' "$merged")
    for dir in "$UIHOME" "$WEBROOT"; do
        [ -d "$dir" ] || continue
        cp -f "$merged" "$dir/pending.yaml" 2>/dev/null
        chmod 644 "$dir/pending.yaml" 2>/dev/null
    done
    stamp=$(date '+%Y-%m-%d %H:%M')
    tmp_subs=$SUBS.tmp
    awk -v n="$name" -v s="$stamp" -v c="$nodes" -F'\t' 'BEGIN{OFS="\t"}
        $1==n { print n, $2, s, c; next }
        { print }
    ' "$SUBS" >"$tmp_subs" 2>/dev/null
    [ -s "$tmp_subs" ] && mv -f "$tmp_subs" "$SUBS"
    echo "fetched=$name"
    echo "node_count=$nodes"
    echo "pending=1"
    return 0
}
