# 第三方组件

本模块会下载并分发以下第三方二进制/数据，**它们各自的许可证独立于本项目**：

| 组件 | 用途 | 许可证 | 来源 |
| :-- | :-- | :-- | :-- |
| [mihomo](https://github.com/MetaCubeX/mihomo) | 代理内核（`bin/mihomo`，`module/scripts/mihomo.sh` 自动下载） | **GPL-3.0** | https://github.com/MetaCubeX/mihomo |
| [meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) | GeoIP / GeoSite / MMDB 数据（内核运行时按需下载） | GPL-3.0 | https://github.com/MetaCubeX/meta-rules-dat |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) | WebUI 界面框架 | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix) | WebUI 组件库 | Apache-2.0 | https://github.com/miuix-kotlin-multiplatform/miuix |

## 关于 GPL-3.0

模块打包的 `mihomo` 二进制以 **GPL-3.0** 授权发布。你若重新分发本模块（含该二进制），
需要一并遵守 GPL-3.0：保留许可证与版权声明、提供对应源码的获取方式。

- mihomo 源码：https://github.com/MetaCubeX/mihomo
- 本项目自身代码同样以 GPL-3.0 发布（见 `LICENSE`），因此二者兼容。

内核不在本仓库中（见 `.gitignore`），由 `./build.sh` 从官方 release 下载，或由模块的
「更新内核」功能在设备上下载。
