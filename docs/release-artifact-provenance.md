# 插件发行物来源记录

每次插件 Release 会额外发布 `release-provenance.json` 和 `SHA256SUMS`。来源清单记录：

- 严格 SemVer 版本、Release tag 和 tag 对应的完整源码 commit。
- Java 主版本与构建所固定的 Plugin SDK、JLShell Link Java、JavaFX、Gson 版本。
- fat JAR 的文件名、字节数和 SHA-256。

Release workflow 会先确认 tag 对应的 commit 已包含在 `main`，再验证、打包和发布。校验和
用于检测下载损坏；它不是发布者数字签名，也不能取代未来的客户端签名验证。清单只包含
公开构建元数据，不写入凭据、机器信息或部署配置。
