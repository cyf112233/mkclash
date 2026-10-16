#!/system/bin/sh
# late_start service: start the core once the system is up, then keep it alive.

ROOT=/data/adb/mihomo
CTL="$ROOT/mihomo.sh"

# wait for the system to finish booting (max ~3 min)
i=0
while [ $i -lt 90 ]; do
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
    sleep 2
    i=$((i + 1))
done
sleep 5

[ -x "$CTL" ] || [ -f "$CTL" ] || exit 0

# Make sure the controller secret exists and the controller port is not already taken
# by another proxy (very common: many users run a second clash/mihomo). This is a no-op
# when the configured port is free.
sh "$CTL" port-auto --no-restart >/dev/null 2>&1

if [ -f "$ROOT/autostart" ]; then
    sh "$CTL" core start >/dev/null 2>&1
fi

# watchdog: restart if the core died (every 30s), and refresh the control
# script when the module is updated in place.
while true; do
    sleep 30
    # `.stopped` is set when the user stops the core from the panel (and cleared on the
    # next boot): without it the watchdog resurrected the process seconds later, which made
    # the stop button look broken.
    if [ -f "$ROOT/autostart" ] && [ ! -f "$ROOT/.stopped" ]; then
        state=$(sh "$CTL" core status 2>/dev/null | grep -m 1 '^running=')
        if [ "$state" != "running=1" ]; then
            sh "$CTL" core start >/dev/null 2>&1
        fi
    fi
done
