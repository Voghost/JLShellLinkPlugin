# 交付安全门禁

本轮覆盖运行依赖漏洞、受 Git 管理的工作树秘密扫描，以及 Website 镜像漏洞。
新增 CycloneDX 许可覆盖与 SPDX 表达式检查；缺失声明、不认识的许可和不在许可策略内的第三方依赖阻止发布。真实平台及综合产品验收继续由第 10 章跟踪。

## 执行与失败条件

- 固定 Trivy 0.75.0，下载官方 Linux x64/macOS ARM64 二进制并核对固定 SHA-256。
- 漏洞数据库实时更新；网络、下载、解析或扫描失败立即阻止发布，不使用 `continue-on-error`。
- CycloneDX 中任何 HIGH/CRITICAL 漏洞均阻止发布，包含尚无修复版本的公告。
- 所有秘密发现均阻止发布。扫描 Git 管理的当前工作树，不复制忽略文件、本地凭据、构建目录或 `.git`。
- 不读取仓库的忽略清单；不添加广泛例外。误报须单独核对并提交明确的修复。
- 原始秘密结果只放在权限 0700 的系统临时目录，退出时删除；控制台和归档只包含数量及规则 ID，不包含匹配值、代码片段和机器路径。
- 漏洞汇总包含包版本、公告 ID、修复版本、输入 SHA-256 和来源提交；只有全部扫描完成才写 `passed: true`。

## CI 和发布

Link、插件在 Linux Java 构建之后扫描。正式发布再次执行扫描，不能使用之前的 CI 结果替代。
Website Jenkins 在镜像构建之前扫描前后端 SBOM 和代码，在推送及部署之前扫描两个实际镜像。
Docker 镜像通过隔离临时目录的 `docker save` 归档读取，无需向扫描器容器暴露 Docker socket。

Jenkins 执行节点需要 Python 3.9+、既有 Docker CLI，以及到 GitHub 官方下载端点和 Trivy 公共数据库的 HTTPS 出站访问。
无需新增生产入站端口或密钥。扫描需要额外时间和镜像归档磁盘空间；空间不足时流水线失败，禁止跳过门禁部署。
可通过 `JLSHELL_SECURITY_CACHE` 指定仅供本执行节点使用的数据库缓存目录；仍会检查数据库更新。

## 复核

```sh
python3 scripts/security/scan.py --secrets --sbom path/to/bom.json --output security-report.json
```

传入文件必须由当前候选构建生成。扫描通过只说明当前数据库和扫描规则未发现阻断项，不代表许可审核、综合业务验收或正式发行已经完成。

## 固定内部制品的独立构建验收（DIST-02）

CI 的 `clean-link-integration` 单独使用全新 Maven 仓库，不加载依赖缓存，也不访问相邻 Link 源码。`.github/link-integration-revision` 固定已实际发布的上游完整提交；版本由提交生成，不能填浮动 SNAPSHOT 或 `latest`。首次引用为 `a1dc176438770398856f634885ef2b5fb071c5eb`，由 [Link 发布 run 38049443725](https://github.com/Voghost/JLShellLink/actions/runs/38049443725) 实际发布。

构建执行 Java 21 完整 `clean verify`，独立读取所下载 core/transport/client JAR 的来源 manifest，拒绝缺失、混用来源、重复 revision 字段或符号链接；归档版本、来源、大小和 SHA-256，以及实际 SBOM/安全门禁汇总。凭据仅由既有 GitHub Actions 包读取权限提供，不归档 Maven settings 或完整仓库。此 job 不发布插件或部署生产；常规三平台仍验证 POM 固定的 0.1.3。下一次切换内部候选先完成上游实际发布，再通过 PR 更新 revision 文件。

构建与来源校验通过不代表真实桌面、网络、平台安装或正式发行通过；这些按第 10 章另行实测。检查器的离线边界验证：

```sh
python3 scripts/test-integration-artifacts.py
```

## 许可覆盖

策略位于 `scripts/security/licenses.py`。内部 JLShell 坐标单独标为内部组件，不虚构其开源许可证。
第三方组件必须有允许的许可声明，`AND` 必须全部满足，`OR` 必须至少有完整允许分支；非法表达式拒绝。
Bouncy Castle 的上游许可名称按其官方 MIT 声明归一；Jitsi 的 Public Domain 只匹配固定坐标/版本。
未启用的 UPnP 与 JNA 被排除，实际 ICE/STUN 和业务传输必须继续通过测试；不以关闭许可门禁处理它们。
Website 的 khroma 2.1.0 包缺少 npm license 字段，通过固定版本自带许可文本 SHA-256 核对后补入 MIT；换版本或换许可文本须重新复核。
本检查是依赖许可风险门禁；复制、再分发时仍须保留上游 LICENSE/NOTICE，不把本报告当作许可授权文件。
