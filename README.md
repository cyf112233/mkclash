# mkclash —— 带 Miuix 风格 WebUI 的 mihomo 内核模块

**mkclash** 是一个 Magisk / KernelSU 模块：内置 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash.Meta）安卓预编译内核，
开机自启 + 看门狗守护；同时提供一个用 **Compose Multiplatform（Kotlin/JS）+ [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 0.9.4**
编写、由 KernelSU WebUI 承载的图形界面（小米 HyperOS 原生观感）。

界面不是网页套壳：它是真正的 Compose 画布（CanvasKit），编译成一个 JS bundle 放进模块的 `webroot/`，
通过 KernelSU 的 `ksu.exec` 直接以 root 身份读写模块目录、下载并替换内核。

> 模块 id / 名称：`mkclash`（安装到 `/data/adb/modules/mkclash`）；内核数据目录仍是 `/data/adb/mihomo`。

---

## 功能

**代理（Clash 常规能力）**

| 页面 | 内容 |
| :-- | :-- |
| 首页 | 内核运行状态 / 版本 / 代理模式、实时上传统计与速率、活动连接数、内存占用、启动·停止·重启、TUN 与局域网开关、快捷入口；连接异常时给出可操作提示（重新检测 / 手动连接） |
| 代理 | 所有策略组、当前选中节点与延迟（组的延迟取所选节点）、单节点与整组延迟测试、节点切换（成员折叠显示，展开上限 60） |
| 规则 | 规则总数、`SearchBar` 搜索 `payload`/`proxy`、逐条展示规则类型与命中策略 |
| 连接 | 活动连接列表（域名/链路/规则/上下行）、单条关闭、全部关闭（二次确认）、连接详情 |
| 日志 | 内核启动后 `tail -f` 效果（优先 `/logs` WebSocket 推送，离线时回退文件镜像）、2 秒刷新、级别着色、**仅当停在末尾时才自动跟随**、清空（二次确认） |

**内核与配置**

| 页面 | 内容 |
| :-- | :-- |
| 更新内核 | 查询 GitHub 最新 release（4 个来源自动回退）、显示当前/最新版本、来源与资源名、可选架构（arm64-v8 / armv7 / amd64 / 386）、可选 GitHub 加速前缀、**手动指定版本**兜底、下载→解压→`mihomo -v` 校验→备份→替换→自动重启，**启动失败自动回滚** |
| 订阅管理 | 添加/删除机场订阅（删除二次确认、危险色）、一键更新（下载后合并本模块的控制器/端口/DNS/TUN 覆盖项，`mihomo -t` 校验通过才写入）、字段内联校验提示 |
| 配置编辑 | 直接编辑 `config.yaml`，保存前 `mihomo -t` 校验、旧配置备份为 `config.yaml.bak`、界面侧热重载、**未保存就返回会二次确认** |
| 连接 | 控制器的连接来源 / 地址 / 状态 / 端口占用 / 密钥，一键「自动选择空闲端口」，以及**手动指定任意 mihomo** |
| 设置 | 内核状态、TUN、日志级别、重启内核（二次确认）、重载配置、**开机自启开关**、连接入口、主题（跟随系统/浅色/深色）、刷新间隔、测速地址、内核架构、日志自动滚动 |
| 关于 | 运行环境（KernelSU 接口、HTTP 后端、API 地址、模块 id）与路径说明 |

**两种打开方式（同一个界面）**

| 方式 | 能力 |
| :-- | :-- |
| KernelSU / WebUI X 里的模块 WebUI | 全部功能：面板 + 启停内核 / 配置 / 订阅 / 更新内核（走 `ksu.exec`） |
| 浏览器打开 `http://127.0.0.1:9090/ui` | 面板全部功能（状态/代理/规则/连接/日志/模式切换）；内核由 mihomo 自己托管，同源、无 CORS |

**模块侧**

- `post-fs-data.sh`：创建数据目录、投放控制脚本、按需播种内核二进制与默认配置（**随机 `secret`**）、补全 `external-controller` / CORS / **`external-ui`** 配置、清理陈旧 pid。
- `service.sh`：等待开机完成 → **检查控制器端口是否被别的代理占用（占用则自动改用空闲端口）** → 启动内核，30 秒看门狗自动拉起。
- `action.sh`：KernelSU / Magisk 模块卡片的“操作”按钮 = 启动/停止内核。
- `uninstall.sh`：停止内核；保留用户的配置与二进制（`/data/adb/mihomo`），便于重装复用。
- 控制脚本每次启动/换端口都会刷新 `webroot/api.json`（`{host, port, secret}`），面板同源读它即可连上内核。

---

## 目录结构

```
.
├── build.sh                     # 一键：编译 WebUI → 组装模块 → 打包 zip
├── dist/                        # 产物：mkclash-<version>-<variant>.zip
├── module/                      # 模块骨架（zip 的内容）
│   ├── module.prop
│   ├── post-fs-data.sh
│   ├── service.sh
│   ├── action.sh
│   ├── uninstall.sh
│   ├── config/config.yaml       # 默认配置模板
│   ├── scripts/mihomo.sh        # 控制脚本（WebUI 的唯一后端入口）
│   ├── bin/mihomo               # 安卓预编译内核（构建时下载，默认 arm64-v8）
│   └── webroot/                 # 构建产物：index.html + mihomo-ui.js + CanvasKit wasm
└── ui/                          # WebUI 源码（Kotlin/JS + Compose + Miuix）
    ├── build.gradle.kts
    └── src/jsMain/
        ├── resources/index.html
        └── kotlin/
            ├── Main.kt                    # ComposeViewport 入口
            └── mihomo/
                ├── core/                  # KSU 桥、Shell、HTTP 传输、mihomo API、全局状态
                └── ui/                    # 主题、页面骨架、UiPrefs、App/MainShell、screens/
```

---

## 构建

前置：JDK 21（Gradle 工具链）、`zip`、能访问 Maven 中央仓库与 npm（Kotlin/JS 需要 Node，本机已安装即可；
Gradle 用项目里的 wrapper 或环境中的 `gradle`）。

```bash
./build.sh                          # 默认 arm64-v8a，完整构建（生产 webpack，会压缩）
./build.sh --dev                    # 不压缩的 bundle：webpack 快很多、内存峰值低（手机/内存小时用）
./build.sh --variant armv7          # 其它架构（构建时下载对应内核）
./build.sh --skip-core              # 不打包内核（体积小，首次使用请在 WebUI 里更新内核）
./build.sh --skip-ui                # 只重新打包模块（复用已有 webpack 产物）
./build.sh --core-version v1.19.32    # 固定内核版本（默认读 module/core.version）
NODE_OPTIONS=--max-old-space-size=1024 ./build.sh   # 内存很紧时限制 webpack 堆
```

产物：`dist/mkclash-<version>-<variant>.zip`，直接在 Magisk / KernelSU 管理器里刷入。

### 只重编译 WebUI（推荐参数）

```bash
cd ui

# 生产包（压缩，体积 ~4.9 MB，界面最跟手）—— 发布/日常都用这个
NODE_OPTIONS=--max-old-space-size=1536 \
  cd ui && ./gradlew jsBrowserProductionWebpack --console=plain

# 开发包（不压缩，~26 MB，编译只要 2 分钟；只在快速改 UI 时用）
NODE_OPTIONS=--max-old-space-size=1536 \
  cd ui && ./gradlew jsBrowserDevelopmentWebpack --console=plain

# 输出目录（Kotlin 2.2+）：ui/build/kotlin-webpack/js/{production,development}Executable/
# 打包进模块：cd .. && ./build.sh --skip-ui
```

**本项目的实际工具链**（这些版本是写死的，换版本要同步改 `ui/build.gradle.kts`）：

| 组件 | 版本 | 备注 |
| :-- | :-- | :-- |
| Gradle | 9.8.1 | `ui/gradlew`（wrapper 已入库），本机也可用 `/opt/gradle-9.8.1/bin/gradle` |
| Kotlin | 2.4.21 | `kotlin("multiplatform")` + `plugin.serialization` + `plugin.compose` |
| Compose Multiplatform | 1.12.1 | Web Canvas 目标（`js { }` + `binaries.executable()`） |
| Miuix | 0.9.4 | `miuix-ui` / `miuix-preference` / `miuix-icons` / `miuix-nav` |
| JDK | 21 | |
| 内存参数 | `NODE_OPTIONS=--max-old-space-size=1536`、`org.gradle.jvmargs=-Xmx2560m` | 见 `ui/gradle.properties` |

> **在手机上构建**：webpack 是整个流程的内存与 CPU 峰值（约 1.5–2 GB RSS，生产压缩要 13–16 分钟）。
> 建议：先 `gradle --stop` 释放守护进程 → 用 `setsid nohup … &` 把构建脱离会话跑（熄屏/切后台不会被杀）→
> 完成后 `./build.sh --skip-ui` 打包。内存紧就加 `NODE_OPTIONS` 或用 `--dev`。

### 性能与卡顿相关的默认值

- 轮询间隔默认 **3 秒**（设置里可调 1–30 秒）；`ksu.exec` 是同步桥，每次调用都会短暂阻塞 WebView 线程。
- 流量/连接只在**首页**和**连接**页轮询，打开二级页时自动停掉；WebView 切到后台（`document.hidden`）时全部暂停。
- 日志页 2 秒/300 行（WebSocket 在线时由内核推送）；连接列表最多渲染 200 条，规则最多 300 条。

---

## 安装与使用

1. 在 Magisk / KernelSU（KernelSU Next、APatch 亦可）中刷入 `dist/mkclash-*.zip`，重启。
2. 首次开机时模块会：生成**随机访问密钥**（写入 `config.yaml` 的 `secret`）、检查 `127.0.0.1:9090` 是否被
   别的代理占用（占用就自动改用第一个空闲端口）、按 `autostart` 开关决定是否拉起内核。
3. 打开 KernelSU 管理器中 `mkclash` 模块的 **WebUI**（网页图标）即可看到界面。
4. 首次使用建议顺序：**订阅管理** 添加订阅并更新 → **首页** 切换模式/开 TUN → **更新内核** 保持最新。
   不想开机自启就去 **设置 → 开机自启** 关掉。

> KernelSU 才提供模块 WebUI（`webroot/`）。Magisk 下模块本身工作正常（自启、看门狗、action 按钮、配置与内核更新），
> 但没有网页入口；可用浏览器打开 `webroot/index.html` 查看只读状态（无 root 通道）。

### 数据与路径

| 路径 | 说明 |
| :-- | :-- |
| `/data/adb/mihomo/mihomo.sh` | 控制脚本（WebUI 所有特权操作的入口，可命令行直接调用） |
| `/data/adb/mihomo/config.yaml` | 当前配置；`config.yaml.bak` 为上一次备份 |
| `/data/adb/mihomo/bin/mihomo` | 内核二进制；`mihomo.bak` 为回滚副本 |
| `/data/adb/mihomo/subscriptions.tsv` | 订阅列表：`名称<TAB>URL<TAB>更新时间<TAB>节点数` |
| `/data/adb/mihomo/run/mihomo.log` | 内核日志 |
| `/data/adb/mihomo/run/mihomo.pid` | 进程号 |
| `/data/adb/mihomo/run/exec.log` | 面板调用控制脚本的审计轨迹（最多 300 行，排查"界面没反应"用） |
| `/data/adb/modules/mkclash/webroot/api.json` | 控制器坐标（host/port/secret），面板同源读取 |
| `/data/adb/mihomo/autostart` | 存在即开机自启（默认存在） |

### 面板是怎么连上内核的（自动，无需配置）

```
1. ksu.exec → mihomo.sh api-info        模块页面里优先走脚本（同时解锁模块功能）
2. 同源 fetch → webroot/api.json         没有 KSU 桥也能拿到 host/port/secret
3. 页面同源推断                           被 mihomo 托管时（http://ip:9090/ui）
4. 手动地址                               仅在用户显式「保存并连接」后优先
```

HTTP 通道同样会自动选：面板与 API 同源时直接用浏览器 `fetch`；
否则通过 `ksu.exec` 调用设备的 `curl`（缺失回退 `wget`，再不行回退 `fetch`）。
因此 **只有面板功能时完全不需要 root 桥**，需要"启停内核/改配置/订阅/更新内核"时才需要 KernelSU / WebUI X 宿主。

### 控制脚本命令

```bash
sh /data/adb/mihomo/mihomo.sh core start|stop|restart|status|toggle
sh /data/adb/mihomo/mihomo.sh api-info          # host= / port= / secret=
sh /data/adb/mihomo/mihomo.sh core-info         # path= / version= / size= / arch= / variant= / running=
sh /data/adb/mihomo/mihomo.sh logs 200
sh /data/adb/mihomo/mihomo.sh config-show
sh /data/adb/mihomo/mihomo.sh config-install    # 安装并校验 run/config.new.yaml
sh /data/adb/mihomo/mihomo.sh sub-list | sub-add <名称> <URL> | sub-remove <名称> | sub-update <名称|all>
sh /data/adb/mihomo/mihomo.sh core-check [加速前缀] [架构]
sh /data/adb/mihomo/mihomo.sh resolve-latest [加速前缀]
sh /data/adb/mihomo/mihomo.sh core-update <版本|latest> [架构] [加速前缀]
sh /data/adb/mihomo/mihomo.sh autostart on|off
sh /data/adb/mihomo/mihomo.sh geo-update [加速前缀]
sh /data/adb/mihomo/mihomo.sh sub-ua [UA]         # 订阅 User-Agent（默认 clash-verge/v1.6.0）
sh /data/adb/mihomo/mihomo.sh sub-fetch <名称>       # 只下载+合并到 webroot/pending.yaml，不重启内核
sh /data/adb/mihomo/mihomo.sh state                  # host/port/secret/autostart/owner/running/version/ua
sh /data/adb/mihomo/mihomo.sh set <键> <值>           # 持久化配置（mode/tun.enable/log-level/…）
sh /data/adb/mihomo/mihomo.sh port-status | port-auto
sh /data/adb/mihomo/mihomo.sh job <kind> [参数]      # 后台任务，立即返回（结果写 webroot/job.json）
sh /data/adb/mihomo/mihomo.sh job clear
```

所有命令的输出都是 `key=value` 行，便于脚本与界面解析；失败时输出 `error=...`。

### 版本获取与下载的网络策略

`api.github.com` 在不少网络里是**直接超时**的（国内尤其常见），所以“检查更新”会依次尝试 4 个独立来源，
并在 `source=` 里告诉你是哪一个生效的：

| 顺序 | 来源 | 说明 |
| :-- | :-- | :-- |
| 1 | `source=github-api` | 官方 REST API，元数据最全，需要 `api.github.com` |
| 2 | `source=github-redirect` | `github.com/.../releases/latest` 的 302 跳转，不需要 API |
| 3 | `source=jsdelivr` | `data.jsdelivr.com` 的包信息接口（GitHub 不可达时通常仍可用） |
| 4 | `source=atom` | `releases.atom` 订阅源，跳过 `Prerelease-Alpha` 之类条目 |

每一级都会**先走你填的加速前缀、再直连**；四个来源全部失败时，界面会显示明确原因，并给出
**“手动指定版本”**输入框兜底（填 `v1.19.32` 或 `1.19.32` 都能装）。

**下载内核**同样会自动换源，顺序是：你填的前缀 → 直连 → 内置加速器
（`https://ghfast.top/`、`https://gh-proxy.com/`、`https://ghproxy.net/`），成功的那一条会在输出里标成
`downloaded_from=<url>`。也就是说，即使 `api.github.com` 被墙、甚至 `github.com` 的下载域名也被墙，
只要这些公共加速器能通，就能装上/更新内核。

### Geo 数据的下载时机

默认配置里**只用不需要 GeoIP 数据库的规则**（RFC1918 `IP-CIDR` + `MATCH`），所以即使设备首次开机还没有网络，
内核也能正常起来。等你在 WebUI 里导入订阅、订阅自带 `GEOIP`/`GEOSITE` 规则时，内核才会按 `geox-url`
下载 `GeoIP.dat` / `Geosite.dat`（各十几 MB，走 jsDelivr）；也可以手动执行 `mihomo.sh geo-update` 预取。

启动流程对这次下载是宽容的：校验失败只会输出 `warn=` 并继续尝试启动；真正的配置错误会让内核立刻退出，
界面会收到 `error=内核进程意外退出` 以及日志末尾几行。

---

## 实现说明

- **入口**：`Main.kt` 用 `androidx.compose.ui.window.ComposeViewport("compose-root")` 把 Compose 画布挂到 `index.html` 的容器上。
- **KernelSU 的 `exec` 是回调式 API，不返回值**（最容易踩的坑）：
  ```js
  ksu.exec(command, '{}', callbackName)
  window[callbackName] = (errno, stdout, stderr) => { ... }
  ```
  本项目按这个契约实现（`core/Ksu.kt`），并兼容返回 `Promise` / 结果对象 / JSON 字符串的老宿主，外加 120 秒超时兜底。
  把 `exec` 当同步调用时：**命令其实会执行，但界面永远拿不到输出** —— 表现就是"读不到密钥 → 401，
  所有特权操作静默失效"。排查时先看 `/data/adb/mihomo/run/exec.log` 有没有对应命令。
- **不依赖 WebView 的 CORS / mixed-content 行为**：界面默认通过 `ksu.exec` 调用设备上的 `curl`（缺失时回退 `wget`，
  再不行才用浏览器的 `fetch`）访问 `http://127.0.0.1:9090`，因此不存在 https 页面请求 http 本机端口被拦截的问题；
  同时模块默认写入 `external-controller-cors.allow-origins: ['*']`，浏览器直开也能只读查看。
- **文件读写**：配置等文本以 UTF-8 → base64 分块（24KB/块）写入临时文件后用 `base64 -d` 落盘，
  避免 shell 引号与参数长度限制破坏内容。
- **状态层**：`AppStore` 是一个持有 Compose state 的 object（WebView 中没有可靠的 lifecycle 宿主），
  由 WebSocket 推送驱动（`/traffic`、`/connections`、`/memory`、`/logs`）；连接失败时自动回退到 HTTP 轮询（间隔可在设置里改，默认 3 秒）。
- **危险操作**（关闭全部连接、删除订阅、覆盖配置、替换内核）都有二次确认；内核替换失败会自动回滚。
- **控制端口冲突是自动处理 + 显式可见的**：`external-controller` 默认 `127.0.0.1:9090`，如果设备上已经有别的代理
  （clash/mihomo/sing-box 的 API）占着它，本模块的内核会绑定失败，界面就会连到**别的实例**上（表现为 401）。
  处理方式有三层：
  1. 开机时 `service.sh` 调 `port-auto --no-restart`，被占用就自动改用 9090–9199 中第一个空闲端口并写回配置；
  2. 启动输出会带 `warn=控制端口 ... 被其它代理占用（返回 401）...`；
  3. 界面 **设置 → 控制器** 显示「端口占用：被其它代理占用」，并提供**一键「自动选择空闲端口」**（`port-auto`），
     改完自动重启内核、重新读取端口与密钥。
- **访问密钥**：安装时随机生成 16 位十六进制串写入 `config.yaml` 的 `secret`（`ensure_secret` 在每次启动时兜底补生成）；
  WebUI 通过 `api-info` 读取同一份配置，因此界面和内核永远一致。

## 已知限制

- 界面需要 WebView 支持 WebAssembly（Android System WebView 80+ 基本都没问题）；过低版本请升级 WebView。
- KernelSU 的 `exec` 走 shell，密集轮询会有开销：默认 3 秒一次，且只在首页/连接页轮询、页面切后台就停。
- 仅 arm64 之外的架构需要构建时指定 `--variant`，或在 WebUI 的“更新内核”里选择对应架构。
- 订阅更新会覆盖 `config.yaml`（保留控制器/端口/模式/日志级别/IPv6/geox/DNS 与 TUN 覆盖项），
  自定义规则较多时建议先备份。
- **`webroot/api.json` 的权衡**：它让面板在浏览器里也能自动连上内核（含 `secret`），代价是"能访问
  `127.0.0.1:9090` 的本机应用也能读到它"。默认配置把控制器绑在 `127.0.0.1`（局域网访问不到），
  要暴露到局域网（`allow-lan: true` / `0.0.0.0:9090`）时建议删掉 `webroot/api.json` 并把 `secret` 换成自己的；
  此时面板仍可通过 KernelSU 宿主里的脚本通道（`api-info`）自动连接。
