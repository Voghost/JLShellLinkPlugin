# JLShell Link Plugin

JLShell Link 的独立私有插件工程，预定仓库为 `Voghost/JLShellLinkPlugin`。项目生成
两个可以分别安装的 JLShell 插件 JAR：

- Program 插件 `com.jlshell.link.program`：管理 Connector 身份和一隧道一进程的
  生命周期，提供运行状态、隧道开关、项目 Agent 意图和安装发布物能力。
- Session 插件 `com.jlshell.link.session`：通过全局 capability 调用 Program，支持
  三平台检测、SFTP 安全上传 Agent 和开发联调隧道。

当前阶段不会自动下载二进制，也不会保存账号令牌。用户需要在 Program 设置页显式
配置 `jlshell-connector`、Connector 身份文件和 Agent 发布目录。账号自动取票、Agent
注册/启动和签名清单验证仍属于后续批次。

## 阶段 3 第一批能力

Program 插件注册以下稳定 capability：

- `link.runtime.status`
- `link.tunnel.open`
- `link.tunnel.close`
- `link.project.agent-intent`
- `link.agent.install-spec`

Connector 只使用 `127.0.0.1:0` 打开本地监听。签名票据写入插件私有运行目录中的
0600 临时文件，Connector 报告监听地址后立即删除；插件停用时终止全部子进程。

项目创建页可以勾选 Agent 引导。Session 部署时先检测远端操作系统和架构，再从
配置目录选择以下固定名称之一：

```text
jlshell-agent-linux-x64
jlshell-agent-macos-arm64
jlshell-agent-windows-x64.exe
```

上传使用随机临时文件，本地和远端 SHA-256 一致后才执行 `chmod 700`（Unix）并替换
正式文件。当前摘要用于传输完整性校验；在签名发布清单启用前，配置的本地发布目录
仍必须被视为可信输入。

## SDK 与本地构建

默认从私有 GitHub Packages 获取 `com.jlshell:plugin-api:0.1.36`。先复制
`settings.example.xml` 到仓库外，并通过环境变量提供 GitHub 用户名和具有
`read:packages` 权限的令牌。

SDK 尚未发布时，可先在相邻 JLShell 仓库安装当前 API，再覆盖依赖版本。项目创建与
Host 事件需要包含阶段 1 扩展的最新本地 SDK：

```bash
cd ../JLShell
mvn -pl plugin-api -am install

cd ../JLShellLinkPlugin
mvn verify -Djlshell.plugin-api.version=0.1.0.RELEASE
```

独立 fat JAR 位于：

```text
link-program-plugin/target/link-program-plugin-0.1.0-SNAPSHOT-fat.jar
link-session-plugin/target/link-session-plugin-0.1.0-SNAPSHOT-fat.jar
```

`link-plugin-distribution/target/plugins/` 汇集同样的两个产物。Plugin API、JavaFX、
SLF4J 和 Logback 均由宿主提供，不打入 fat JAR。本工程设置
`maven.deploy.skip=true`，不会发布到 Maven Central 或其他 Maven 仓库。
