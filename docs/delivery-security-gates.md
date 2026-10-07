# 交付安全门禁

本轮覆盖运行依赖漏洞、受 Git 管理的工作树秘密扫描，以及 Website 镜像漏洞。
许可证审核、运行时包内 JRE/WinSW 扫描及正式平台验收继续由第 10 章跟踪，不能以本门禁代替。

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
