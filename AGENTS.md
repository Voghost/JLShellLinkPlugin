# JLShellLinkPlugin 协作说明

始终使用简体中文沟通。

本目录是独立 Git 仓库，预定远程为私有 `Voghost/JLShellLinkPlugin`。提交不得与
父级聚合目录、JLShell、JLShellWebsite 或 JLShellLink 混合。

## 分支流程

- 日常开发和功能分支以 `develop` 为基线，提交先进入 `develop`。
- `main` 只接收 GitHub 上的 `develop -> main` Pull Request，不得直接推送开发提交。
- 发布标签只从 `main` 创建。

- 使用 Java 21 和 Maven 3.9+。
- 产品只发布一个 `com.jlshell.link.program` Program 插件、一个 ServiceLoader 入口和
  一个 fat JAR。SSH 会话功能必须通过 Plugin SDK 1.1.0 的 `sessionIntegration()`
  贡献，禁止重新引入独立 Session 插件或第二个插件 ID。
- `net.oomn.jlshell:plugin-api`、JavaFX 和宿主日志依赖必须为 provided/排除项。
- 稳定公共能力为 `link.runtime.status`；破坏性变更需要新增能力名或版本。
- Program 插件独占 Connector 进程生命周期；各会话控制器只能通过 Program 内部能力
  请求开关隧道，不得各自维护 Connector 进程。
- Agent 部署必须先验证本地发布物摘要，上传到随机临时文件、验证远端摘要后再替换；
  不得把票据、账号令牌或 SSH 凭据写入普通 PluginStorage 或日志。
- 浏览器登录必须使用回环 Authorization Code + PKCE；JWT 和稳定设备 ID 只能进入
  SecureStorage，会话控制器只能通过 Program 内部能力请求目录和短期票据。
- Agent 注册凭据只能通过 SFTP 临时文件下发，Unix 先设为 0600 再替换；不得进入
  命令行。部署使用 systemd 用户服务、macOS LaunchAgent 或 Windows SCM，并保持
  用户明确确认；Windows SCM 必须使用专属虚拟服务账户并限制运行目录 ACL。自动下载、
  签名清单、回滚保护和平台代码签名尚未完成。
- 不提交 GitHub Token、PAT、账号信息、机器码或 SSH 凭据。

验证命令：

```bash
mvn -U verify
```
