# Java Link 插件交付候选

本候选使用已发布 Link Java 0.1.3、Plugin SDK 1.5.0，最低宿主版本修正为 0.1.67。
已发布宿主 0.1.66 的 tag 尚未包含 v2 路由提交；独立 SDK 发布不等于宿主安装包已包含这些接口。

官方 Agent 发布公钥随本插件资源独立交付；空配置使用官方锚，自建发布方显式配置的公钥替换默认锚。
插件仍验证原始清单签名、归档和远端摘要；安装仍需要明确确认。
发行方密钥轮换须独立更新 Website 和插件信任锚，不能从下载响应自动添加信任。

正式插件 tag 必须来自 main，且等待宿主候选及三平台 Agent 安装、双出口业务验收通过。
普通 fat JAR 不内置 Rust 或 Java Agent 平台包。旧 Rust 类尚未从源码删掉；退役在发布成功及恢复窗口结束后执行。

## 可安装同源集成候选（2026-10-12）

宿主 0.1.67 已从 main 正式发布到 GitHub。插件 CI 的三平台及全新 Maven 仓库检查使用
`.github/link-integration-revision` 固定的不可变内部 Maven 版本；当前固定 Link
`3cc036d4b47447a9c7aad36c5bb767acbf458567`，与 Agent 0.1.4-rc.2 签名候选同源。
正式 POM 的已发布依赖仍为 0.1.3；内部版本仅用于候选验收，不创建插件稳定标签。

检查成功后保留唯一 Program fat JAR、SBOM、源码/依赖版本与 SHA-256 记录 14 天，
使用 `plugin-installable-candidate-*` 和 `plugin-installable-link-integration-candidate`
artifact 下载。全新仓库检查独立核对实际下载的 core/transport/client 三个 JAR 来源。

已验证前轮三个 CI fat JAR 摘要、唯一 Program 入口、最低宿主 0.1.67、宿主 API 排除及旧运行时排除。
这些前轮产物使用 0.1.3，不计作最新网络修复验收；本轮新候选还须完成实包核对和真实桌面业务。
