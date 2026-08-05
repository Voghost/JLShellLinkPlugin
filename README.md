# JLShell Link Plugin

JLShell Link 的独立私有插件工程，仓库为 `Voghost/JLShellLinkPlugin`。项目只生成一个
Program 插件 JAR，插件标识为 `com.jlshell.link.program`。它统一管理 Connector 身份、
项目 Agent 意图、Agent 部署以及隧道生命周期；账号会话由 JLShell 宿主统一管理。

Program 插件使用 SDK 1.4.0 的连接前路由、项目管理和宿主账号会话网关。它不再注册
会话页面：隧道必须在 SSH 会话建立前就绪，否则保存的内网 SSH 地址无法使用。一个
Program 插件、一个 ServiceLoader 入口和一个插件 ID 即可完成整个流程。

正式插件 JAR 会直接内置 Linux x64、macOS ARM64、Windows x64 的 Connector 和 Agent。
首次激活时自动解包到 `~/.jlshell/link/runtime/<version>/`，逐文件核对清单中的大小和
SHA-256，并设置仅当前用户可读写执行的权限。Website 默认使用
`https://jlshell.oomn.net`，Connector、身份文件和 Agent 目录无需普通用户填写；设置页
只把路径覆盖保留在折叠的高级配置中。账号令牌只保存在 JLShell 宿主的加密存储中，
不传给插件、不传给会话控制器，也不写入日志。

登录后 Program 会从 Website 读取当前 Free/Plus/Pro entitlement，并校验 Program
插件策略。Agent 目录、取票和隧道能力都在实际入口再次检查，不依赖界面按钮防绕过。
Free 用户可直接使用已完成 PeerId
持钥验证的桌面设备领取一次 14 天 Pro 试用；客户端只上传产品域 SHA-256 机器指纹，
不会上传或保存操作系统原始机器标识。

## 当前连接模型

1. 用户在 Website 的“JLShell Link Agent”创建只显示一次的 15 分钟注册令牌，并在目标服务器安装 Agent。
2. Agent 消费令牌后获得独立节点凭据；Website 管理 Agent 状态、轮换/吊销节点凭据和精确 IP:端口目标授权。
3. JLShell 项目管理页只选择一个在线 Agent。保存的 SSH 主机和端口仍是实际内网目标，例如 `192.168.31.20:22`。
4. 打开 SSH 连接时，插件确认该项目绑定、Agent 在线和精确目标授权，向 Website 取单流票据并启动本机回环 Connector；宿主仅把这一连接映射至 `127.0.0.1` 或 `::1`，保留原 SSH 用户名、认证方式和凭据。
5. SSH 会话关闭、连接失败、重连或插件停用时，Connector 隧道会随资源租约释放。

稳定 capability 仅供 Program 内部和受控扩展使用：

- `link.runtime.status`
- `link.tunnel.open`
- `link.tunnel.close`
- `link.account.status`
- `link.subscription.status`、`link.subscription.refresh`、`link.subscription.trial.claim`
- `link.catalog`、`link.ticket.issue`

Connector 只使用 `127.0.0.1:0` 打开本地监听。签名票据写入插件私有运行目录中的
0600 临时文件，Connector 报告监听地址后立即删除；插件停用时终止全部子进程。

新建和已有项目的管理页都会展示账号、内置运行时和 Connector 状态，并列出当前账号
已在线且存在精确目标授权的 Agent。Agent 的下载、注册令牌和服务器安装说明统一放在
Website，避免在某个已打开 SSH 会话的页面内配置全局 Program 插件。

内置运行时包含以下固定文件名：

```text
jlshell-agent-linux-x64
jlshell-agent-macos-arm64
jlshell-agent-windows-x64.exe
```

账号登录由 JLShell 的“账号设置”统一完成，Link 不再显示、创建或保存第二份
登录态。宿主仅向 Link 暴露非敏感账号状态、设备 ID 和受限的 Link 控制平面请求；随后
插件调用 Connector 对一次性 challenge 签名，将宿主设备绑定到 Connector PeerId。
套餐、试用与 Program 策略也通过相同的宿主请求通道查询和校验，插件不会读取或续期
账号令牌。Agent 令牌不进入 JLShell；只在远程服务器的受限文件中被 Agent 消费一次。

## SDK 与本地构建

默认从 Maven Central 获取 SDK
`net.oomn.jlshell:plugin-api:1.4.0` 和同版本 `program-api`，构建不需要 GitHub Packages 凭据或额外
Maven `settings.xml`。

需要联调尚未发布的宿主 API 变更时，可先在相邻 JLShell 仓库安装当前 API，再覆盖
依赖版本。项目创建与 Host 事件需要包含阶段 1 扩展的最新本地 SDK：

```bash
cd ../JLShell
mvn -pl plugin-api,program-api -am install

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
