# JLShell Link Plugin

JLShell Link 的独立私有插件工程，预定仓库为 `Voghost/JLShellLinkPlugin`。项目生成
两个可以分别安装的 JLShell 插件 JAR：

- Program 插件 `com.jlshell.link.program`：注册全局能力
  `link.runtime.status`，阶段 0 固定返回未配置状态。
- Session 插件 `com.jlshell.link.session`：以 `sessionId=null` 调用上述程序级能力，
  并在会话标签页展示结果。

阶段 0 不下载或启动 Rust Connector，不部署 Agent，也不包含订阅与账号功能。

## SDK 与本地构建

默认从私有 GitHub Packages 获取 `com.jlshell:plugin-api:0.1.36`。先复制
`settings.example.xml` 到仓库外，并通过环境变量提供 GitHub 用户名和具有
`read:packages` 权限的令牌。

SDK 尚未发布时，可先在相邻 JLShell 仓库安装当前 API，再覆盖依赖版本：

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

