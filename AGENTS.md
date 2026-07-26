# JLShellLinkPlugin 协作说明

始终使用简体中文沟通。

本目录是独立 Git 仓库，预定远程为私有 `Voghost/JLShellLinkPlugin`。提交不得与
父级聚合目录、JLShell、JLShellWebsite 或 JLShellLink 混合。

- 使用 Java 21 和 Maven 3.9+。
- Program 与 Session 插件必须保持独立 ServiceLoader 入口和独立 fat JAR。
- `com.jlshell:plugin-api`、JavaFX 和宿主日志依赖必须为 provided/排除项。
- 稳定公共能力为 `link.runtime.status`；破坏性变更需要新增能力名或版本。
- 阶段 0 不下载/执行 Rust 二进制，不写订阅逻辑，不安装 Agent。
- 不提交 GitHub Token、PAT、账号信息、机器码或 SSH 凭据。

验证命令：

```bash
mvn verify -Djlshell.plugin-api.version=0.1.0.RELEASE
```

