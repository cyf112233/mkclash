#!/system/bin/sh
# Runs on every boot, very early. Must stay fast and idempotent:
# it is the only place that seeds /data/adb/mihomo for both Magisk and KernelSU
# (KernelSU does not run customize.sh).

MODDIR=${0%/*}
ROOT=/data/adb/mihomo

mkdir -p "$ROOT" "$ROOT/bin" "$ROOT/run" 2>/dev/null
chmod 755 "$ROOT" 2>/dev/null
chmod 700 "$ROOT/run" 2>/dev/null

# --- control script: always refreshed from the module -------------------
if [ -f "$MODDIR/scripts/mihomo.sh" ]; then
    cp -f "$MODDIR/scripts/mihomo.sh" "$ROOT/mihomo.sh"
    chmod 755 "$ROOT/mihomo.sh"
fi

# --- core binary: seeded once, never overwritten (updates live in $ROOT) --
if [ ! -x "$ROOT/bin/mihomo" ] && [ -f "$MODDIR/bin/mihomo" ]; then
    cp -f "$MODDIR/bin/mihomo" "$ROOT/bin/mihomo"
    chmod 755 "$ROOT/bin/mihomo"
fi

# --- default config ------------------------------------------------------
if [ ! -f "$ROOT/config.yaml" ] && [ -f "$MODDIR/config/config.yaml" ]; then
    secret=$(head -c 8 /dev/urandom 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
    [ -z "$secret" ] && secret=$(date +%s)$RANDOM
    sed "s/__SECRET__/$secret/" "$MODDIR/config/config.yaml" >"$ROOT/config.yaml"
    chmod 600 "$ROOT/config.yaml"
fi

# --- keep the user's tun/dns edits but make sure the API stays reachable --
if [ -f "$ROOT/config.yaml" ]; then
    grep -qE '^[[:space:]]*external-controller[[:space:]]*:' "$ROOT/config.yaml" || \
        echo 'external-controller: 127.0.0.1:9090' >>"$ROOT/config.yaml"
    grep -qE '^[[:space:]]*external-controller-cors[[:space:]]*:' "$ROOT/config.yaml" || {
        {
            echo 'external-controller-cors:'
            echo "  allow-origins:"
            echo "    - '*'"
            echo '  allow-private-network: true'
        } >>"$ROOT/config.yaml"
    }
    # Let mihomo serve the panel itself (same origin as the API).
    #
    # It must live *inside* the core's working directory: mihomo rejects `external-ui`
    # paths outside `-d <dir>` with a fatal "path is not subpath of home directory or
    # SAFE_PATHS" error, which stops the whole core from starting.
    if [ -d "$MODDIR/webroot" ]; then
        mkdir -p "$ROOT/ui" 2>/dev/null
        want=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null | head -n 1)
        have=$(cat "$ROOT/ui/.version" 2>/dev/null)
        if [ "$want" != "$have" ] || [ ! -f "$ROOT/ui/mihomo-ui.js" ]; then
            rm -rf "$ROOT/ui" 2>/dev/null
            mkdir -p "$ROOT/ui" 2>/dev/null
            cp -f "$MODDIR/webroot/"* "$ROOT/ui/" 2>/dev/null
            chmod 644 "$ROOT/ui/"* 2>/dev/null
            echo "$want" >"$ROOT/ui/.version"
        fi
        if grep -qE '^[[:space:]]*external-ui[[:space:]]*:' "$ROOT/config.yaml"; then
            sed -i "s|^[[:space:]]*external-ui[[:space:]]*:.*|external-ui: $ROOT/ui|" "$ROOT/config.yaml"
        else
            echo "external-ui: $ROOT/ui" >>"$ROOT/config.yaml"
        fi
    fi
fi

# --- subscriptions file --------------------------------------------------
[ -f "$ROOT/subscriptions.tsv" ] || : >"$ROOT/subscriptions.tsv"

# --- autostart defaults to on -------------------------------------------
[ -f "$ROOT/autostart" ] || touch "$ROOT/autostart"

# --- drop a stale pid from the previous boot -----------------------------
if [ -f "$ROOT/run/mihomo.pid" ]; then
    pid=$(cat "$ROOT/run/mihomo.pid" 2>/dev/null)
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$ROOT/run/mihomo.pid"
    fi
fi

# --- WebUI assets (KernelSU serves these from the module's webroot) ------
if [ -d "$MODDIR/webroot" ]; then
    chmod -R 755 "$MODDIR/webroot" 2>/dev/null
fi

exit 0
