#!/system/bin/sh
# KernelSU / Magisk "action" button: toggle the core and report the result.

ROOT=/data/adb/mihomo
CTL="$ROOT/mihomo.sh"

[ -f "$CTL" ] || {
    echo "控制脚本缺失：$CTL"
    exit 1
}

result=$(sh "$CTL" core toggle 2>&1)

if sh "$CTL" core status 2>/dev/null | grep -q '^running=1'; then
    echo "mihomo 已启动"
else
    echo "mihomo 已停止"
fi
echo "$result"
