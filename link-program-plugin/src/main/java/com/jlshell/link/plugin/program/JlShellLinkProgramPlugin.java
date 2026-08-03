package com.jlshell.link.plugin.program;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.program.session.LinkSessionContribution;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.event.ProjectDeletedEvent;
import com.jlshell.plugin.api.event.SessionOpenedEvent;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.storage.PluginStorage;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class JlShellLinkProgramPlugin implements JlShellProgramPlugin {

    private static final long MAX_AGENT_BYTES = 200L * 1024 * 1024;
    private ProgramPluginContext context;
    private ConnectorProcessManager connectorManager;
    private LinkAccountClient accountClient;
    private BundledRuntimeManager runtimeManager;
    private final List<Registration> registrations = new ArrayList<>();
    private final Map<String, LinkBindingStore.SessionReference> sessionReferences = new ConcurrentHashMap<>();
    private LinkBindingStore bindingStore;

    @Override
    public String id() {
        return LinkPluginContract.PROGRAM_PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "JLShell Link";
    }

    @Override
    public String version() {
        return LinkPluginContract.VERSION;
    }

    @Override
    public String author() {
        return "Voghost";
    }

    @Override
    public String minHostVersionInclusive() {
        return LinkPluginContract.MIN_HOST_VERSION;
    }

    @Override
    public String description() {
        return "Provides process-wide JLShell Link runtime capabilities.";
    }

    @Override
    public void activate(ProgramPluginContext context) {
        this.context = context;
        runtimeManager = new BundledRuntimeManager();
        BundledRuntimeManager.PreparedRuntime bundled = runtimeManager.prepare();
        connectorManager = new ConnectorProcessManager(
                ConnectorConfiguration.load(context.storage(), bundled));
        accountClient = new LinkAccountClient(context.storage(), context.secureStorage(), connectorManager);
        bindingStore = new LinkBindingStore(context.storage());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.RUNTIME_STATUS_CAPABILITY)
                .description("Return the process-wide JLShell Link runtime status.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(runtimeStatus()))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TUNNEL_OPEN_CAPABILITY)
                .description("Start a loopback-only Connector tunnel from a signed Link ticket.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> accountClient.authorizeSession("link.tcp-tunnel")
                        .thenCompose(ignored -> connectorManager.open(args)))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TUNNEL_CLOSE_CAPABILITY)
                .description("Stop a Connector tunnel owned by this plugin process.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> connectorManager.close(args))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY)
                .description("Return whether the current session project requested Agent guidance.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        projectAgentIntent(requiredString(args.getAsJsonObject(), "sessionId"))))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.AGENT_INSTALL_SPEC_CAPABILITY)
                .description("Resolve a locally configured Agent binary for a supported remote platform.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> accountClient.authorizeSession("link.agent-deploy")
                        .thenCompose(ignored -> agentInstallSpec(args)))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.ACCOUNT_STATUS_CAPABILITY)
                .description("Return the encrypted Program-level Link account session state.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(accountClient.status()))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.ACCOUNT_LOGIN_CAPABILITY)
                .description("Start browser Authorization Code + PKCE desktop login.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.startLogin()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.ACCOUNT_LOGOUT_CAPABILITY)
                .description("Revoke and erase the encrypted Link account session.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.logout()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.SUBSCRIPTION_STATUS_CAPABILITY)
                .description("Return the cached Link plan, trial and plugin policy state.")
                .requiresSession(false).handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        accountClient.subscriptionStatus())).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.SUBSCRIPTION_REFRESH_CAPABILITY)
                .description("Refresh Link entitlements and Program/Session plugin policies.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.refreshSubscription()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TRIAL_CLAIM_CAPABILITY)
                .description("Claim the one-time 14-day Pro trial for the verified desktop device.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.claimTrial()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.LINK_CATALOG_CAPABILITY)
                .description("List owned Agents, targets and available Relays without exposing the account token.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.catalog()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TICKET_ISSUE_CAPABILITY)
                .description("Issue a one-stream signed ticket for the registered Connector identity.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.issueTicket(args)).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.AGENT_CHALLENGE_CAPABILITY)
                .description("Issue an Agent proof-of-possession challenge.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.agentChallenge(args)).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.AGENT_REGISTER_CAPABILITY)
                .description("Register a proven Agent and its initial exact SSH target.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.registerAgent(args)).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.AUTHORITY_CAPABILITY)
                .description("Download the public Link ticket Authority keyring.")
                .requiresSession(false).handler((args, capabilityContext) -> accountClient.authority()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.BINDING_GET_CAPABILITY)
                .description("Return the Agent binding for the current saved connection.")
                .requiresSession(false).handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        bindingStore.get(sessionReference(
                                requiredString(args.getAsJsonObject(), "sessionId"))))).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.BINDING_SAVE_CAPABILITY)
                .description("Bind the current saved connection to an Agent and exact target.")
                .requiresSession(false).handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        saveBinding(args.getAsJsonObject()))).build());
        if (context.projectIntegration().available()) {
            registrations.add(context.projectIntegration().register(new LinkProjectContribution(
                    context.storage(), accountClient, connectorManager, runtimeManager)));
        }
        if (context.sessionIntegration().available()) {
            registrations.add(context.sessionIntegration().register(new LinkSessionContribution()));
        }
        if (context.hostEvents().available()) {
            registrations.add(context.hostEvents().subscribe(SessionOpenedEvent.class, event -> {
                sessionReferences.put(event.sessionId(),
                        new LinkBindingStore.SessionReference(event.projectId(), event.connectionId()));
            }));
            registrations.add(context.hostEvents().subscribe(ProjectDeletedEvent.class, event -> {
                PluginStorage storage = context.storage();
                if (storage != null) {
                    storage.remove(LinkProjectContribution.projectKey(event.projectId()));
                }
                bindingStore.removeProject(event.projectId());
                sessionReferences.entrySet().removeIf(entry ->
                        event.projectId().equals(entry.getValue().projectId()));
            }));
        }
        context.info("JLShell Link program plugin activated");
    }

    @Override
    public void deactivate() {
        if (context == null) {
            return;
        }
        for (Registration registration : registrations) {
            try {
                registration.close();
            } catch (RuntimeException error) {
                context.warn("Cannot release JLShell Link registration: " + error.getMessage());
            }
        }
        registrations.clear();
        sessionReferences.clear();
        for (String capability : List.of(
                LinkPluginContract.RUNTIME_STATUS_CAPABILITY,
                LinkPluginContract.TUNNEL_OPEN_CAPABILITY,
                LinkPluginContract.TUNNEL_CLOSE_CAPABILITY,
                LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY,
                LinkPluginContract.AGENT_INSTALL_SPEC_CAPABILITY)) {
            context.capabilityRegistry().unregister(capability);
        }
        for (String capability : List.of(
                LinkPluginContract.ACCOUNT_STATUS_CAPABILITY,
                LinkPluginContract.ACCOUNT_LOGIN_CAPABILITY,
                LinkPluginContract.ACCOUNT_LOGOUT_CAPABILITY,
                LinkPluginContract.SUBSCRIPTION_STATUS_CAPABILITY,
                LinkPluginContract.SUBSCRIPTION_REFRESH_CAPABILITY,
                LinkPluginContract.TRIAL_CLAIM_CAPABILITY,
                LinkPluginContract.LINK_CATALOG_CAPABILITY,
                LinkPluginContract.TICKET_ISSUE_CAPABILITY,
                LinkPluginContract.AGENT_CHALLENGE_CAPABILITY,
                LinkPluginContract.AGENT_REGISTER_CAPABILITY,
                LinkPluginContract.AUTHORITY_CAPABILITY,
                LinkPluginContract.BINDING_GET_CAPABILITY,
                LinkPluginContract.BINDING_SAVE_CAPABILITY)) {
            context.capabilityRegistry().unregister(capability);
        }
        accountClient.close();
        accountClient = null;
        bindingStore = null;
        connectorManager.close();
        connectorManager = null;
        runtimeManager = null;
        context.info("JLShell Link program plugin deactivated");
        context = null;
    }

    @Override
    public Node settingsView(ProgramPluginContext context) {
        ConnectorConfiguration configuration = ConnectorConfiguration.load(
                context.storage(), runtimeManager.prepared()).normalized();
        Label title = new Label("JLShell Link");
        Label overall = new Label();
        overall.setWrapText(true);
        Label accountState = new Label();
        Label subscriptionState = new Label();
        subscriptionState.setWrapText(true);
        Label runtimeState = new Label();
        Label connectorState = new Label();

        Button login = new Button("登录 JLShell 账号");
        Button logout = new Button("退出账号");
        Button refresh = new Button("刷新状态");
        Button trial = new Button("领取 14 天 Pro 试用");
        Button repair = new Button("重新准备内置运行时");

        TextField connector = new TextField(text(configuration.connectorBinary()));
        connector.setPromptText("默认自动使用插件内置 Connector");
        TextField identity = new TextField(text(configuration.identityFile()));
        identity.setPromptText("Connector 身份文件路径");
        TextField agents = new TextField(text(configuration.agentBundleDirectory()));
        agents.setPromptText("默认自动使用插件内置三平台 Agent");
        TextField website = new TextField(accountClient.configuredBaseUrl());
        website.setPromptText(LinkAccountClient.DEFAULT_BASE_URL);

        Runnable update = () -> {
            JsonObject runtime = runtimeManager.status();
            JsonObject connectorStatus = connectorManager.status();
            JsonObject account = accountClient.status();
            overall.setText(readinessText(runtime, connectorStatus, account));
            runtimeState.setText("内置运行时：" + runtime.get("state").getAsString()
                    + " · " + runtime.get("message").getAsString());
            connectorState.setText("Connector：" + connectorStatus.get("state").getAsString()
                    + " · 活跃隧道 " + connectorStatus.get("activeTunnels").getAsInt());
            accountState.setText("账号：" + account.get("state").getAsString()
                    + " · " + account.get("baseUrl").getAsString());
            JsonObject subscription = account.getAsJsonObject("subscription");
            subscriptionState.setText(subscriptionText(subscription));
            boolean authenticated = "AUTHENTICATED".equals(account.get("state").getAsString());
            login.setDisable(authenticated || !connectorStatus.get("available").getAsBoolean());
            logout.setDisable(!authenticated);
            refresh.setDisable(!authenticated);
            trial.setDisable(!authenticated || !"TRIAL_AVAILABLE".equals(
                    subscription.get("state").getAsString()));
        };

        Button save = new Button("保存高级配置");
        save.setOnAction(event -> {
            try {
                ConnectorConfiguration updated = new ConnectorConfiguration(
                        path(connector.getText()), path(identity.getText()), path(agents.getText())).normalized();
                accountClient.configureBaseUrl(website.getText());
                updated.save(context.storage());
                connectorManager.configure(updated);
                update.run();
                context.showNotification("JLShell Link 配置已保存", NotificationLevel.INFO);
            } catch (RuntimeException error) {
                overall.setText("配置无效：" + error.getMessage());
                context.showNotification("JLShell Link 配置无效", NotificationLevel.ERROR);
            }
        });
        repair.setOnAction(event -> {
            BundledRuntimeManager.PreparedRuntime prepared = runtimeManager.prepare();
            ConnectorConfiguration.useBundledDefaults(context.storage());
            ConnectorConfiguration defaults = ConnectorConfiguration.load(context.storage(), prepared).normalized();
            connector.setText(text(defaults.connectorBinary()));
            agents.setText(text(defaults.agentBundleDirectory()));
            connectorManager.configure(defaults);
            update.run();
        });
        refresh.setOnAction(event -> {
            refresh.setDisable(true);
            accountClient.refreshSubscription().whenComplete((value, error) ->
                    javafx.application.Platform.runLater(() -> {
                        refresh.setDisable(false);
                        update.run();
                        if (error != null) overall.setText("套餐状态刷新失败：" + rootMessage(error));
                    }));
        });
        login.setOnAction(event -> accountClient.startLogin().whenComplete((value, error) ->
                javafx.application.Platform.runLater(() -> {
                    update.run();
                    if (error != null) overall.setText("登录失败：" + error.getMessage());
                    else overall.setText("浏览器登录已打开，完成后点击“刷新状态”。");
                })));
        logout.setOnAction(event -> accountClient.logout().whenComplete((value, error) ->
                javafx.application.Platform.runLater(update)));
        trial.setOnAction(event -> {
            trial.setDisable(true);
            accountClient.claimTrial().whenComplete((value, error) ->
                    javafx.application.Platform.runLater(() -> {
                        update.run();
                        if (error == null) {
                            overall.setText("14 天 Pro 试用已开通，Link 能力现已可用。");
                        } else {
                            overall.setText("试用领取失败：" + rootMessage(error));
                        }
                    }));
        });

        VBox advanced = new VBox(8, new Label("服务地址"), website,
                new Label("Connector 覆盖路径"), connector,
                new Label("身份文件"), identity,
                new Label("Agent 发布目录覆盖路径"), agents, save);
        advanced.setPadding(new Insets(8));
        TitledPane advancedPane = new TitledPane("高级配置（一般无需修改）", advanced);
        advancedPane.setExpanded(false);

        Label note = new Label("默认配置会自动解包并校验 Connector 与三平台 Agent。登录态、"
                + "Connector 和所有 SSH Session 由当前 Program 插件统一管理；进入 SSH 会话后按向导安装 Agent。");
        note.setWrapText(true);
        HBox accountActions = new HBox(8, login, logout, trial, refresh);
        HBox runtimeActions = new HBox(8, repair);
        VBox root = new VBox(10, title, overall, accountState, subscriptionState, runtimeState, connectorState,
                accountActions, runtimeActions, note, advancedPane);
        root.setPadding(new Insets(12));
        update.run();
        return root;
    }

    private JsonObject projectAgentIntent(String sessionId) {
        JsonObject result = new JsonObject();
        String projectId = sessionReference(sessionId).projectId();
        boolean requested = false;
        if (projectId != null && context.storage() != null) {
            requested = Boolean.parseBoolean(context.storage().get(
                    LinkProjectContribution.projectKey(projectId), "false"));
        }
        result.addProperty("requested", requested);
        if (projectId == null) {
            result.add("projectId", com.google.gson.JsonNull.INSTANCE);
        } else {
            result.addProperty("projectId", projectId);
        }
        return result;
    }

    private JsonObject saveBinding(JsonObject args) {
        LinkBindingStore.SessionReference session = sessionReference(requiredString(args, "sessionId"));
        int port;
        try {
            port = args.get("targetPort").getAsInt();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("targetPort is required", error);
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("targetPort is invalid");
        return bindingStore.save(session, requiredString(args, "agentId"),
                requiredString(args, "targetIp"), port);
    }

    private LinkBindingStore.SessionReference sessionReference(String sessionId) {
        return sessionReferences.getOrDefault(sessionId,
                new LinkBindingStore.SessionReference(null, null));
    }

    private CompletableFuture<com.google.gson.JsonElement> agentInstallSpec(com.google.gson.JsonElement args) {
        try {
            if (args == null || !args.isJsonObject()) {
                throw new IllegalArgumentException("platform and architecture are required");
            }
            JsonObject object = args.getAsJsonObject();
            String platform = requiredString(object, "platform");
            String architecture = requiredString(object, "architecture");
            Path binary = connectorManager.agentBinary(platform, architecture);
            long size = Files.size(binary);
            if (size < 1 || size > MAX_AGENT_BYTES) {
                throw new IllegalStateException("Agent binary is empty or exceeds 200 MiB");
            }
            JsonObject result = new JsonObject();
            result.addProperty("platform", platform);
            result.addProperty("architecture", architecture);
            result.addProperty("path", binary.toString());
            result.addProperty("size", size);
            result.addProperty("sha256", sha256(binary));
            return CompletableFuture.completedFuture(result);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    JsonObject runtimeStatus() {
        JsonObject runtime = runtimeManager.status();
        JsonObject connector = connectorManager.status();
        JsonObject account = accountClient.status();
        boolean runtimeReady = runtime.get("available").getAsBoolean();
        boolean connectorReady = connector.get("available").getAsBoolean();
        boolean authenticated = "AUTHENTICATED".equals(account.get("state").getAsString());
        JsonObject subscription = account.getAsJsonObject("subscription");
        String state = !runtimeReady ? "RUNTIME_MISSING"
                : !connectorReady ? "CONNECTOR_NOT_READY"
                : !authenticated ? "SIGNED_OUT" : subscription.get("state").getAsString();
        String nextAction = !runtimeReady ? "REINSTALL_OR_REPAIR_PLUGIN"
                : !connectorReady ? "REPAIR_CONNECTOR"
                : !authenticated ? "LOGIN" : switch (state) {
                    case "READY" -> "OPEN_SESSION";
                    case "TRIAL_AVAILABLE" -> "START_TRIAL_OR_UPGRADE";
                    case "CHECKING" -> "REFRESH_SUBSCRIPTION";
                    default -> "CONTACT_ADMIN_OR_UPGRADE";
                };
        JsonObject result = new JsonObject();
        result.addProperty("available", runtimeReady && connectorReady && authenticated && "READY".equals(state));
        result.addProperty("state", state);
        result.addProperty("nextAction", nextAction);
        if (connector.has("version")) result.add("version", connector.get("version").deepCopy());
        result.add("runtime", runtime.deepCopy());
        result.add("connector", connector.deepCopy());
        result.add("account", account.deepCopy());
        result.add("subscription", subscription.deepCopy());
        return result;
    }

    private static String readinessText(JsonObject runtime, JsonObject connector, JsonObject account) {
        if (!runtime.get("available").getAsBoolean()) {
            return "需要修复：插件未包含完整运行时。请重新安装正式插件包，或点击重新准备运行时。";
        }
        if (!connector.get("available").getAsBoolean()) {
            return "需要修复：Connector 尚未就绪。点击重新准备内置运行时后刷新状态。";
        }
        if (!"AUTHENTICATED".equals(account.get("state").getAsString())) {
            return "还差一步：登录 JLShell 账号。Session 会直接复用这里的登录态，不会重复登录。";
        }
        String subscription = account.getAsJsonObject("subscription").get("state").getAsString();
        if (!"READY".equals(subscription)) {
            return switch (subscription) {
                case "TRIAL_AVAILABLE" -> "当前是 Free 套餐，可领取 14 天 Pro 试用或升级套餐。";
                case "UPGRADE_REQUIRED" -> "当前套餐不包含 JLShell Link，请升级 Plus 或 Pro。";
                case "DISABLED_BY_ADMIN" -> "JLShell Link 已被管理员停用，请联系管理员。";
                case "VERSION_NOT_SUPPORTED" -> "当前插件版本不在管理员允许范围内，请升级或回退插件。";
                case "CHECK_FAILED" -> "无法检查套餐状态，请确认网站服务和网络连接。";
                default -> "正在检查套餐与插件策略，请稍后刷新状态。";
            };
        }
        return "JLShell Link 已就绪。打开项目中的 SSH 会话即可检测并安装 Agent。";
    }

    private static String subscriptionText(JsonObject subscription) {
        String state = subscription.get("state").getAsString();
        if (!subscription.has("entitlement") || subscription.get("entitlement").isJsonNull()) {
            return "套餐：" + state;
        }
        JsonObject entitlement = subscription.getAsJsonObject("entitlement");
        String plan = entitlement.get("plan").getAsString();
        String suffix = entitlement.has("effectiveUntil") && !entitlement.get("effectiveUntil").isJsonNull()
                ? " · 有效至 " + entitlement.get("effectiveUntil").getAsString() : "";
        return "套餐：" + plan + " · 权限状态 " + state + suffix;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String value = object.get(name).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String text(Path path) {
        return path == null ? "" : path.toString();
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value.trim());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
