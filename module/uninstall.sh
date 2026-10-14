#!/system/bin/sh
# Stop the core when the module is removed.
# The user's own files (config.yaml, subscriptions.tsv, bin/mihomo) are kept on
# purpose: they can be re-used after reinstalling the module.

ROOT=/data/adb/mihomo
CTL="$ROOT/mihomo.sh"

if [ -f "$CTL" ]; then
    sh "$CTL" core stop >/dev/null 2>&1
fi

# make sure nothing survives
if command -v pkill >/dev/null 2>&1; then
    pkill -f "$ROOT/bin/mihomo" >/dev/null 2>&1
fi

rm -f "$ROOT/run/mihomo.pid" 2>/dev/null

echo "mihomo 内核已停止。配置与二进制保留在 $ROOT，如需彻底清理请手动删除该目录。"
exit 0
