# JLShell Link Plugin

JLShell Link 的独立私有插件工程，仓库为 `Voghost/JLShellLinkPlugin`。项目只生成一个
Program 插件 JAR，插件标识为 `com.jlshell.link.program`。它统一管理 Connector 身份、
账号与安全存储、项目 Agent 意图、Agent 部署以及隧道生命周期。

Program 插件通过 JLShell Plugin SDK 1.1.0 的 `sessionIntegration()` 注册 SSH 会话贡献，
并使用 SDK 1.2.0 的项目管理贡献展示已有项目状态和修复引导。
宿主在用户打开 JLShell Link 时提供当前 `SshSessionContext`，所以三平台检测、SFTP 安全
上传和隧道界面都在同一个 Program 插件中完成，不再发布独立 Session 插件、第二个
ServiceLoader 入口或第二个插件 ID。

正式插件 JAR 会直接内置 Linux x64、macOS ARM64、Windows x64 的 Connector 和 Agent。
首次激活时自动解包到 `~/.jlshell/link/runtime/<version>/`，逐文件核对清单中的大小和
SHA-256，并设置仅当前用户可读写执行的权限。Website 默认使用
`https://jlshell.oomn.net`，Connector、身份文件和 Agent 目录无需普通用户填写；设置页
只把路径覆盖保留在折叠的高级配置中。账号令牌只保存在 Program 插件的宿主
SecureStorage 中，不传给会话控制器或写入日志。

登录后 Program 会从 Website 读取当前 Free/Plus/Pro entitlement，并分别校验同一插件
标识的 Program 与 Session 策略。Agent 安装、注册、目录、取票和隧道能力都在实际
capability 入口再次检查，不依赖界面按钮防绕过。Free 用户可直接使用已完成 PeerId
持钥验证的桌面设备领取一次 14 天 Pro 试用；客户端只上传产品域 SHA-256 机器指纹，
不会上传或保存操作系统原始机器标识。

## 阶段 3 第一批能力

Program 插件注册以下稳定 capability：

- `link.runtime.status`
- `link.tunnel.open`
- `link.tunnel.close`
- `link.project.agent-intent`
- `link.agent.install-spec`
- `link.account.status`、`link.account.login`、`link.account.logout`
- `link.subscription.status`、`link.subscription.refresh`、`link.subscription.trial.claim`
- `link.catalog`、`link.ticket.issue`
- `link.agent.challenge`、`link.agent.register`、`link.authority`

Connector 只使用 `127.0.0.1:0` 打开本地监听。签名票据写入插件私有运行目录中的
0600 临时文件，Connector 报告监听地址后立即删除；插件停用时终止全部子进程。

新建项目默认启用 Agent 引导；已有项目的管理页会展示账号、内置运行时和 Connector
状态、当前套餐、Program/Session 策略以及可执行的登录/试用/修复入口。会话贡献提供检测、确认、上传、注册、服务安装和
连接绑定的分步向导，并从内置目录选择以下固定名称之一：

```text
jlshell-agent-linux-x64
jlshell-agent-macos-arm64
jlshell-agent-windows-x64.exe
```

上传使用随机临时文件，本地和远端 SHA-256 一致后才执行 `chmod 700`（Unix）并替换
正式文件。Session 中的登录按钮调用同一个 Program 账号能力，复用全局 PKCE 登录态，
不会生成第二份账号或令牌。

Program 使用系统浏览器和本机临时回环监听完成 Authorization Code + PKCE S256，
随后调用 Connector 对一次性 challenge 签名，将桌面设备绑定到 Connector PeerId。
会话控制器可通过 Program 内部能力读取账号 Agent/目标目录并自动签发单流票据，但访问
令牌始终留在账号客户端中。

“部署、注册并启动 Agent”会在远端生成 0600 Ed25519 身份、完成持钥注册、登记
`127.0.0.1:22` 精确目标，并通过 SFTP 下发 0600 节点凭据与 Authority keyring。
插件使用原生管理器安装并验证服务：Linux 为 `systemd --user`、macOS 为
`LaunchAgent`、Windows 为 SCM Service；配置更新会重启既有服务。数字 IP SSH
主机地址会作为精确 TCP/QUIC multiaddr 上报，域名不会进入 Rust 数据面的地址列表。

Program 依据宿主 `SessionOpenedEvent` 保存 `projectId + connectionId + agentId +
target` 本地绑定。会话贡献加载账号目录时优先选择当前连接绑定，并自动填入 Agent
心跳上报的直连地址；重连后的新 sessionId 由宿主重新发布事件。

## SDK 与本地构建

默认从 Maven Central 获取支持 Program 会话贡献的 SDK
`net.oomn.jlshell:plugin-api:1.2.0`，构建不需要 GitHub Packages 凭据或额外
Maven `settings.xml`。

需要联调尚未发布的宿主 API 变更时，可先在相邻 JLShell 仓库安装当前 API，再覆盖
依赖版本。项目创建与 Host 事件需要包含阶段 1 扩展的最新本地 SDK：

```bash
cd ../JLShell
mvn -pl plugin-api -am install

cd ../JLShellLinkPlugin
mvn verify -Djlshell.plugin-api.version=0.1.0.RELEASE
```

唯一的 fat JAR 位于：

```text
link-program-plugin/target/link-program-plugin-0.1.0-SNAPSHOT-fat.jar
```

`link-plugin-distribution/target/plugins/` 汇集同一个产物。Plugin API、JavaFX、
SLF4J 和 Logback 均由宿主提供，不打入 fat JAR。本工程设置
`maven.deploy.skip=true`，不会发布到 Maven Central 或其他 Maven 仓库。

正式标签构建要求 `Voghost/JLShellLink` 存在同名标签，并在本仓库配置只读细粒度
`JLSHELL_LINK_RELEASE_TOKEN`。Release Action 下载同名私有运行时包，验证包摘要后才
执行 Maven 打包；缺少清单或任一平台二进制时直接失败，禁止发布“需要用户手填路径”的
残缺插件。
