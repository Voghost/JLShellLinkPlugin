# Security Policy

本仓库是私有商业插件原型。安全问题请使用 GitHub 私有安全报告渠道，不要公开
真实账号、机器标识、SSH 凭据、令牌或未来的订阅票据。

Program 插件只执行插件包内置或高级设置显式覆盖的 Rust Connector，并通过 Session
SFTP 上传 Agent。内置文件解包前后都必须与运行时清单中的大小和 SHA-256 一致，目录
权限为 0700；Connector 固定回环监听，票据只进入 0600 临时文件且就绪后删除，插件
停用时终止子进程。Agent 先上传至随机临时路径，远端 SHA-256 与本地一致后才替换正式
文件，Unix 权限固定为 0700。

桌面授权由 JLShell 宿主使用一次性 PKCE S256 授权码和随机 state 完成，回调只能绑定
127.0.0.1。账号 JWT、稳定设备 ID 与 Website 地址配置只进入宿主 SecureStorage；插件
通过受限的宿主账号会话接口取得脱敏状态并调用 Link API，绝不会读取、保存或接收 JWT。
Agent 凭据经 SFTP 写入随机临时文件，Unix 上先设为 0600 再原子替换，且不会出现在远端
命令行。

试用机器指纹优先读取 Linux machine-id、macOS IOPlatformUUID 或 Windows MachineGuid，
只在进程内按 `jlshell-trial-machine-v1` 产品域执行 SHA-256 后上传；原始标识不进入
SecureStorage、PluginStorage、HTTP 请求或日志。平台标识不可用时只使用排序后的本机
网卡硬件地址作为降级输入，同样只上传摘要。服务端会再使用独立 Pepper 做 HMAC。

套餐快照最多缓存 30 秒。Agent 安装、Agent 注册、目录、票据和隧道入口都会校验
Website 的 Program/Session 插件策略与具体 entitlement；缓存过期后的网络检查失败时
关闭访问，不会按过期缓存降级放行。

当前 SHA-256 只保证插件内文件与清单一致，不能独立证明发布者身份。生产发布前必须增加
插件签名、可信公钥轮换、版本回滚保护和平台代码签名。不得把票据、访问令牌、机器码
或 SSH 凭据写入普通 PluginStorage、进程命令行或日志。Linux systemd 用户服务启用
`NoNewPrivileges`、`PrivateTmp` 和 0077 umask；macOS LaunchAgent 与 Windows
SCM 配置自动拉起。Windows Agent 使用专属 `NT SERVICE\\JLShellLinkAgent` 虚拟账户，
运行目录 ACL 仅保留部署用户、SYSTEM 和该服务账户；服务账户只获得读取和执行权限。
