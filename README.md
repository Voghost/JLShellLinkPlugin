# JLShell Link Plugin

`Voghost/JLShellLinkPlugin` 是 JLShell Link 的独立 Java 21 插件工程，只发布一个
Program fat JAR，插件 ID 为 `com.jlshell.link.program`。插件使用 Plugin SDK 1.5.0 和
JLShell Link Java Client 0.1.2；账号登录和 HTTP 请求由 JLShell 宿主提供。

SSH 建连前的隧道由进程内 Java 客户端创建。客户端代码及其 Netty、KCP、Bouncy Castle
依赖会一起打入 fat JAR，并重定位到插件私有包名；宿主 SDK、JavaFX 和日志实现仍由宿主提供。
发行包不再捆绑 Rust Connector/Agent，也不会为桌面 SSH 路由启动外部 Connector 进程。

## 当前桌面连接流程

1. 用户在 JLShell“账号设置”中登录。插件通过宿主账号请求网关访问 Website，不读取或保存账号 JWT。
2. 项目管理页从 Website 获取在线的 Link v2 Agent 和访问策略；项目只保存选中的 `agentId`。
3. SSH 建连时重新检查 Program/Session 套餐和策略，再确认 Agent 在线、协议版本为 Link v2、节点密钥已绑定。
4. 插件使用 JLShell 宿主的加密存储创建或读取 A 节点密钥与 TLS 身份；Website 设备绑定和每次目标访问均使用宿主授权请求。
5. 当前桌面客户端使用 `RELAY_ONLY`，通过 WSS Relay 建立内层 mTLS 和 HTTP/2 CONNECT，成功后才向宿主返回一次性 `127.0.0.1` 隧道租约。
6. 宿主使用 Plugin SDK 1.5.0 将真实目标身份与回环 socket 分开处理，保留原 SSH 用户名、凭据和严格主机密钥校验；关闭、取消或失败时释放租约。

默认 Relay 地址为 `wss://jlink.oomn.net/link/v2/relay`。可在 Program 插件设置的折叠高级区域调整；设置只存非敏感地址，修改后下次连接重新初始化客户端。

项目连接目标必须是 Website 访问策略允许的数值 IP 和端口。每次连接以及重连都会重新申请访问授权；账号、Agent、策略或节点密钥不匹配时失败关闭。

## 注册和 Agent 状态

Program 插件设置提供“添加 Java 网关”入口，通过当前 JLShell 账号创建 Website 一次性 enrollment token。令牌仅在创建后显示给用户，不写入插件设置或日志。然后按 Website 的 Java Agent 安装说明在 C 上完成注册。

自动 SSH 上传、注册、系统服务安装及跨平台 Java Agent 制品仍需 Link `DIST-01` 交付后完成；插件目前不再回退到旧 Rust Agent 部署流程。当前产品客户端还没有 P2P 自动选路，桌面路由仅使用已经验收的 WSS Relay。

## 数据存储与安全边界

- JLShell 账号会话、JWT 和请求续期只由宿主管理。
- A 的 Ed25519 私钥与 PKCS#12 客户端 TLS 身份只进入 Plugin SDK `SecureStorage`；无法使用宿主加密存储时，客户端拒绝初始化。
- Relay URL、项目 `agentId` 等非秘密设置存入 PluginStorage。
- 每个隧道都由 Website 根据当前账号、A 设备身份、Agent 身份、目标和策略重新授权。Connector 参数、PeerId、多地址和长期访问票据不进入默认路由。
- 本地监听只绑定随机 loopback 端口，并只接受一条 SSH TCP 流；隧道关闭或引擎停用时释放端口、网络资源和待处理任务。

## 构建与依赖

Plugin SDK `net.oomn.jlshell:plugin-api` 和 `program-api` 1.5.0 从 Maven Central 获取。
Link Java 制品 `com.jlshell.link:*:0.1.2` 从 `Voghost/JLShellLink` 的 GitHub Packages 获取。
本地 Maven `settings.xml` 需要配置 `github` server ID、GitHub 用户名和具有 `read:packages`
权限的只读令牌；令牌不得写进仓库。GitHub Actions 使用仓库授权的 `GITHUB_TOKEN`。

```bash
mvn -B -ntp verify
```

Program fat JAR：

```text
link-program-plugin/target/link-program-plugin-<version>-fat.jar
```

`link-plugin-distribution/target/plugins/` 汇集唯一的 Program 插件 JAR。CI 会确认 Java
Link 客户端和已重定位网络依赖存在、旧 Rust 运行时没有进入发行包、宿主 API 没有重复打包。
插件不会发布到 Maven Central。
