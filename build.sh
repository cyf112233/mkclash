#!/usr/bin/env bash
#
# Builds the KernelSU/Magisk module:
#   1. compiles the Compose + Miuix WebUI (Kotlin/JS) into ui/build/dist
#   2. copies the JS bundle + CanvasKit runtime into module/webroot
#   3. makes sure a mihomo (pre-built, Android) core binary is present
#   4. zips module/ into dist/mkclash-<version>-<variant>.zip
#
# Usage:
#   ./build.sh                       # arm64-v8a, full build
#   ./build.sh --variant armv7
#   ./build.sh --dev                 # unminified bundle: much faster / lighter on a phone
#   ./build.sh --skip-ui             # only re-package the module
#   ./build.sh --skip-core           # slim module, core downloaded from the WebUI
#   ./build.sh --core-version v1.19.32
#
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
UI_DIR="$ROOT_DIR/ui"
MODULE_DIR="$ROOT_DIR/module"
DIST_DIR="$ROOT_DIR/dist"
WEBROOT="$MODULE_DIR/webroot"

VARIANT="arm64-v8"
CORE_VERSION=""
SKIP_UI=0
SKIP_CORE=0
DEV_BUNDLE=0

usage() {
    sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --variant) VARIANT="$2"; shift 2 ;;
        --core-version) CORE_VERSION="$2"; shift 2 ;;
        --skip-ui) SKIP_UI=1; shift ;;
        --skip-core) SKIP_CORE=1; shift ;;
        --dev) DEV_BUNDLE=1; shift ;;
        -h|--help) usage ;;
        *) echo "未知参数: $1" >&2; usage ;;
    esac
done

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

command -v zip >/dev/null 2>&1 || die "需要 zip 命令"
command -v gunzip >/dev/null 2>&1 || die "需要 gunzip 命令"

# ---------------------------------------------------------------------------
# 1. WebUI
# ---------------------------------------------------------------------------
if [ "$SKIP_UI" -eq 0 ]; then
    GRADLE_BIN="${GRADLE:-}"
    if [ -z "$GRADLE_BIN" ]; then
        if [ -x "$UI_DIR/gradlew" ]; then
            GRADLE_BIN="$UI_DIR/gradlew"
        elif command -v gradle >/dev/null 2>&1; then
            GRADLE_BIN="gradle"
        else
            die "找不到 gradle，请设置 GRADLE=/path/to/gradle"
        fi
    fi
    say "编译 WebUI（Kotlin/JS + Compose Multiplatform + Miuix）"
    case "$DEV_BUNDLE" in
        1) WEBPACK_TASK="jsBrowserDevelopmentWebpack" ;;
        *) WEBPACK_TASK="jsBrowserProductionWebpack" ;;
    esac
    # webpack is the memory peak of the whole build; keep node from swapping
    (cd "$UI_DIR" && NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=1536}" \
        "$GRADLE_BIN" "$WEBPACK_TASK" --console=plain)
fi

# ---------------------------------------------------------------------------
# 1b. copy the bundle into module/webroot (also runs with --skip-ui)
#
# Kotlin 2.2+ writes webpack output to build/kotlin-webpack/...; older versions
# used build/dist/... — support both. When both a production and a development
# bundle exist, the most recently built one wins (that is what you just compiled).
# ---------------------------------------------------------------------------
DIST_JS=""
for candidate in \
    "$UI_DIR/build/kotlin-webpack/js/productionExecutable" \
    "$UI_DIR/build/kotlin-webpack/js/developmentExecutable" \
    "$UI_DIR/build/dist/js/productionExecutable" \
    "$UI_DIR/build/dist/js/developmentExecutable"; do
    [ -f "$candidate/mihomo-ui.js" ] || continue
    if [ -z "$DIST_JS" ] || [ "$candidate/mihomo-ui.js" -nt "$DIST_JS/mihomo-ui.js" ]; then
        DIST_JS="$candidate"
    fi
done
[ -n "$DIST_JS" ] || die "找不到 WebUI 构建产物（mihomo-ui.js），请先执行不带 --skip-ui 的构建"

say "使用 WebUI 产物：$DIST_JS（$(du -h "$DIST_JS/mihomo-ui.js" | awk '{print $1}')）"
case "$DIST_JS" in
    *developmentExecutable*)
        say "提示：这是未压缩的 development bundle（体积大、WebView 加载慢）；" \
            "正式发布建议用 jsBrowserProductionWebpack 生成 production bundle"
        ;;
esac

say "复制 WebUI 资源到 module/webroot（$DIST_JS）"
rm -rf "$WEBROOT"
mkdir -p "$WEBROOT"
cp -f "$DIST_JS"/*.js "$WEBROOT/" 2>/dev/null || true
cp -f "$DIST_JS"/*.wasm "$WEBROOT/" 2>/dev/null || true

# index.html: use the project template so the KernelSU-specific tweaks survive,
# and inject exactly one <script> tag for the bundle.
cp -f "$UI_DIR/src/jsMain/resources/index.html" "$WEBROOT/index.html"
if ! grep -q 'mihomo-ui.js' "$WEBROOT/index.html"; then
    awk '{ if ($0 ~ /<\/body>/) print "<script src=\"mihomo-ui.js\"></script>"; print }' \
        "$WEBROOT/index.html" >"$WEBROOT/index.html.tmp"
    mv -f "$WEBROOT/index.html.tmp" "$WEBROOT/index.html"
fi

[ -s "$WEBROOT/mihomo-ui.js" ] || die "mihomo-ui.js 缺失，请检查 gradle 构建输出"

# CanvasKit: Compose for Web loads Skiko from a sibling .wasm file. Webpack emits
# it with a hashed name, so a plain copy is enough — only fall back to extracting
# it from the Gradle cache when the bundle produced no wasm at all.
if ! ls "$WEBROOT"/*.wasm >/dev/null 2>&1; then
    say "在 Gradle 缓存中查找 CanvasKit 运行时"
    skiko_jar=$(find "${HOME}/.gradle/caches/modules-2/files-2.1/org.jetbrains.skiko" \
        -name "skiko-js-wasm-runtime-*.jar" 2>/dev/null | head -n 1 || true)
    if [ -n "${skiko_jar:-}" ]; then
        tmp=$(mktemp -d)
        unzip -oq "$skiko_jar" -d "$tmp"
        wasm=$(find "$tmp" -name "*.wasm" | head -n 1 || true)
        if [ -n "${wasm:-}" ]; then
            cp -f "$wasm" "$WEBROOT/"
            say "已提取 $(basename "$wasm")"
        fi
        rm -rf "$tmp"
    fi
fi
say "WebUI 资源：$(cd "$WEBROOT" && ls -1 | tr '\n' ' ')"

# ---------------------------------------------------------------------------
# 2. core binary
# ---------------------------------------------------------------------------
CORE_BIN="$MODULE_DIR/bin/mihomo"
# Remember which ABI/version the cached binary is. Without this a cached arm64 kernel plus
# `--variant armv7` produced a zip that *claims* armv7 and ships arm64 — silently.
CORE_META="$MODULE_DIR/bin/.core-meta"
CORE_CACHE_KEY=""
[ -f "$CORE_META" ] && CORE_CACHE_KEY=$(cat "$CORE_META" 2>/dev/null)
CORE_WANT_KEY=""
PINNED_CORE=$(tr -d ' \r\n' <"$ROOT_DIR/module/core.version" 2>/dev/null || true)
if [ -n "$CORE_VERSION" ]; then
    CORE_WANT_KEY="$VARIANT $CORE_VERSION"
elif [ -n "$CORE_CACHE_KEY" ]; then
    case "$CORE_CACHE_KEY" in
        "$VARIANT "*) CORE_WANT_KEY="$CORE_CACHE_KEY" ;;
        *) CORE_WANT_KEY="mismatch" ;;
    esac
elif [ -n "$PINNED_CORE" ]; then
    # no cached kernel: module/core.version keeps builds reproducible
    # (override with --core-version vX.Y.Z)
    CORE_VERSION="$PINNED_CORE"
    CORE_WANT_KEY="$VARIANT $CORE_VERSION"
fi
if [ "$SKIP_CORE" -eq 0 ]; then
    if [ -x "$CORE_BIN" ] && [ "$VARIANT" != "arm64-v8" ] && [ -z "$CORE_CACHE_KEY" ] && [ -z "$CORE_VERSION" ]; then
        die "bin/mihomo 没有架构记录，无法确认与 --variant $VARIANT 匹配；请加 --core-version <版本> 重新下载"
    fi
    if [ ! -x "$CORE_BIN" ] || [ "$CORE_WANT_KEY" = "mismatch" ]; then
        if [ -z "$CORE_VERSION" ]; then
            say "查询 mihomo 最新版本"
            CORE_VERSION=$(curl -fsSI https://github.com/MetaCubeX/mihomo/releases/latest \
                | tr -d '\r' | awk 'tolower($1) == "location:" {print $2}' | sed 's#.*/tag/##')
            [ -n "$CORE_VERSION" ] || die "无法获取 mihomo 版本号，请用 --core-version 指定"
        fi
        asset="mihomo-android-${VARIANT}-${CORE_VERSION}.gz"
        url="https://github.com/MetaCubeX/mihomo/releases/download/${CORE_VERSION}/${asset}"
        say "下载内核 ${CORE_VERSION} (${VARIANT})"
        mkdir -p "$MODULE_DIR/bin"
        tmpgz=$(mktemp)
        curl -fL --progress-bar -o "$tmpgz" "$url" || die "下载失败：$url"
        gunzip -c "$tmpgz" >"$CORE_BIN"
        chmod 755 "$CORE_BIN"
        rm -f "$tmpgz"
        printf '%s %s\n' "$VARIANT" "$CORE_VERSION" >"$CORE_META"
        say "内核已就位：$(du -h "$CORE_BIN" | awk '{print $1}') ($VARIANT $CORE_VERSION)"
    else
        say "已存在内核二进制：$(du -h "$CORE_BIN" | awk '{print $1}')（${CORE_CACHE_KEY:-未知架构}，用 --skip-core 排除打包）"
    fi
else
    mkdir -p "$MODULE_DIR/bin"
fi

# ---------------------------------------------------------------------------
# 3. permissions + package
# ---------------------------------------------------------------------------
say "整理权限"
chmod 755 "$MODULE_DIR" "$MODULE_DIR/bin" "$MODULE_DIR/scripts" "$MODULE_DIR/config" "$MODULE_DIR/webroot" 2>/dev/null || true
chmod 755 "$MODULE_DIR"/*.sh
chmod 755 "$MODULE_DIR/scripts/"*.sh
[ -f "$CORE_BIN" ] && chmod 755 "$CORE_BIN"
chmod 644 "$MODULE_DIR/module.prop" "$MODULE_DIR/config/"*.yaml
# webroot is read by the manager app, not only by root — keep it world readable
chmod 644 "$MODULE_DIR/webroot/"* 2>/dev/null || true

VERSION=$(awk -F= '/^version=/{print $2}' "$MODULE_DIR/module.prop")
mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/mkclash-${VERSION}-${VARIANT}.zip"
rm -f "$OUT"

say "打包 $OUT"
(cd "$MODULE_DIR" && zip -qr "$OUT" . -x '*.DS_Store' -x '__MACOSX/*' -x "bin/mihomo.version" -x "bin/.core-meta")

say "完成：$OUT ($(du -h "$OUT" | awk '{print $1}'))"
echo
echo "模块内容："
(cd "$MODULE_DIR" && find . -type f | grep -v '^./bin/mihomo$' | sort | sed 's/^/  /')
[ -f "$CORE_BIN" ] && echo "  ./bin/mihomo ($(du -h "$CORE_BIN" | awk '{print $1}'))"
