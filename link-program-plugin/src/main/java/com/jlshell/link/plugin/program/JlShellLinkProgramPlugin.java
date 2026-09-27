package com.jlshell.link.plugin.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.jlshell.link.core.model.ConnectPolicy;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.program.session.LinkSessionStatusContribution;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.event.ProjectDeletedEvent;
import com.jlshell.plugin.api.event.SessionOpenedEvent;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.program.api.AccountSession;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class JlShellLinkProgramPlugin implements JlShellProgramPlugin {

    private ProgramPluginContext context;
    private LinkV2AccountClient v2AccountClient;
    private LinkSubscriptionService subscriptions;
    private LinkClientRuntime linkClientRuntime;
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
        v2AccountClient = new LinkV2AccountClient(context.accountSession());
        subscriptions = new LinkSubscriptionService(context.accountSession());
        bindingStore = new LinkBindingStore(context.storage());
        linkClientRuntime = new LinkClientRuntime(context.accountSession(), context.secureStorage(),
                context.storage(), v2AccountClient);
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.RUNTIME_STATUS_CAPABILITY)
                .description("Return the process-wide JLShell Link runtime status.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(runtimeStatus()))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY)
                .description("Return whether the current session project requested Agent guidance.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        projectAgentIntent(requiredString(args.getAsJsonObject(), "sessionId"))))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.ACCOUNT_STATUS_CAPABILITY)
                .description("Return the non-sensitive host account session state used by Link.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(hostAccountStatus()))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.SUBSCRIPTION_STATUS_CAPABILITY)
                .description("Return cached Link plan and Program/Session policy state.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(subscriptions.status())).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.SUBSCRIPTION_REFRESH_CAPABILITY)
                .description("Refresh Link plan and Program/Session policy state through the host account gateway.")
                .requiresSession(false).handler((args, capabilityContext) -> subscriptions.refresh()).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TRIAL_CLAIM_CAPABILITY)
                .description("Claim the one-time Link trial without exposing the host account token.")
                .requiresSession(false).handler((args, capabilityContext) -> {
                    JsonObject input = args == null || !args.isJsonObject() ? new JsonObject() : args.getAsJsonObject();
                    return subscriptions.claimTrial(requiredString(input, "machineFingerprint"),
                            context.accountSession().snapshot().deviceId());
                }).build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.LINK_CATALOG_V2_CAPABILITY)
                .description("List Java Link Agents registered to the current host account.")
                .requiresSession(false).handler((args, capabilityContext) -> v2AccountClient.agents()
                        .thenApply(agents -> {
                            JsonObject catalog = new JsonObject();
                            catalog.add("agents", agents);
                            return catalog;
                        })).build());
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
                    context.storage(), v2AccountClient, subscriptions, linkClientRuntime, bindingStore)));
        }
        if (context.connectionIntegration().available()) {
            registrations.add(context.connectionIntegration().register(new LinkConnectionRouteContribution(
                    bindingStore, v2AccountClient, subscriptions, linkClientRuntime)));
        }
        if (context.sessionIntegration().available()) {
            registrations.add(context.sessionIntegration().register(new LinkSessionStatusContribution()));
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
                LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY)) {
            context.capabilityRegistry().unregister(capability);
        }
        for (String capability : List.of(
                LinkPluginContract.ACCOUNT_STATUS_CAPABILITY,
                LinkPluginContract.SUBSCRIPTION_STATUS_CAPABILITY,
                LinkPluginContract.SUBSCRIPTION_REFRESH_CAPABILITY,
                LinkPluginContract.TRIAL_CLAIM_CAPABILITY,
                LinkPluginContract.LINK_CATALOG_V2_CAPABILITY,
                LinkPluginContract.BINDING_GET_CAPABILITY,
                LinkPluginContract.BINDING_SAVE_CAPABILITY)) {
            context.capabilityRegistry().unregister(capability);
        }
        if (linkClientRuntime != null) linkClientRuntime.close();
        linkClientRuntime = null;
        v2AccountClient = null;
        subscriptions = null;
        bindingStore = null;
        context.info("JLShell Link program plugin deactivated");
        context = null;
    }

    @Override
    public Node settingsView(ProgramPluginContext context) {
        // Preferences can still enumerate an enabled descriptor after activation failed and
        // rollback cleared the runtime fields. Settings must remain safe to open in that state.
        if (!settingsDependenciesReady()) {
            return unavailableSettingsView();
        }
        Label title = new Label("JLShell Link");
        Label overall = new Label();
        overall.setWrapText(true);
        Label accountState = new Label();
        Label subscriptionState = new Label();
        subscriptionState.setWrapText(true);
        Label runtimeState = new Label();

        Button refresh = new Button("刷新状态");
        Button trial = new Button("领取 14 天 Pro 试用");
        TextField gatewayName = new TextField();
        gatewayName.setPromptText("新网关名称");
        Button addGateway = new Button("添加 Java 网关");
        TextArea enrollment = new TextArea();
        enrollment.setEditable(false);
        enrollment.setWrapText(true);
        enrollment.setVisible(false);
        enrollment.setManaged(false);
        Label enrollmentState = new Label("无需先打开到网关的 SSH 会话。先生成一次性注册信息，再按网站安装指引部署 Java Agent。");
        enrollmentState.setWrapText(true);
        TextField relayUri = new TextField(LinkClientSettings.relayUri(context.storage()));
        relayUri.setPromptText(LinkClientSettings.DEFAULT_RELAY_URI);
        TextField stunServers = new TextField(LinkClientSettings.stunServers(context.storage()));
        stunServers.setPromptText("可选：203.0.113.10:3478,[2001:db8::10]:3478");
        ComboBox<ConnectPolicy> connectPolicy = new ComboBox<>();
        connectPolicy.getItems().setAll(ConnectPolicy.values());
        connectPolicy.setValue(LinkClientSettings.connectPolicy(context.storage()));

        ComboBox<ForwardAgent> forwardAgent = new ComboBox<>();
        forwardAgent.setPromptText("选择在线且启用访问策略的 Java 网关");
        Button refreshForwardAgents = new Button("刷新网关");
        TextField forwardTargetIp = new TextField();
        forwardTargetIp.setPromptText("目标数值 IP，例如 192.168.1.20");
        TextField forwardTargetPort = new TextField();
        forwardTargetPort.setPromptText("目标端口，例如 5432");
        Button openForward = new Button("打开本地转发");
        Button closeForward = new Button("关闭本地转发");
        closeForward.setDisable(true);
        TextArea forwardStatus = new TextArea("本地端口只绑定 127.0.0.1；每条转发只接受一个 TCP 连接。");
        forwardStatus.setEditable(false);
        forwardStatus.setWrapText(true);
        forwardStatus.setPrefRowCount(3);
        AtomicReference<UUID> openForwardId = new AtomicReference<>();

        Runnable update = () -> {
            JsonObject runtime = linkClientRuntime.status();
            JsonObject account = hostAccountStatus();
            JsonObject subscription = subscriptions.status();
            overall.setText(readinessText(runtime, account, subscription));
            runtimeState.setText("Java 客户端：" + runtime.get("state").getAsString()
                    + " · 活跃隧道 " + runtime.get("openTunnels").getAsInt()
                    + " · 等待连接 " + runtime.get("pendingTunnels").getAsInt()
                    + " · 控制信令 " + runtime.get("controlSignalState").getAsString()
                    + pathDiagnostic(runtime));
            accountState.setText("账号：" + account.get("state").getAsString()
                    + " · " + account.get("baseUrl").getAsString());
            subscriptionState.setText(subscriptionText(subscription));
            trial.setDisable(!"TRIAL_AVAILABLE".equals(subscription.get("state").getAsString()));
        };

        Button save = new Button("保存连接配置");
        save.setOnAction(event -> {
            try {
                LinkClientSettings.saveRelayUri(context.storage(), relayUri.getText());
                LinkClientSettings.saveStunServers(context.storage(), stunServers.getText());
                LinkClientSettings.saveConnectPolicy(context.storage(), connectPolicy.getValue());
                linkClientRuntime.reset();
                update.run();
                context.showNotification("JLShell Link 连接配置已保存", NotificationLevel.INFO);
            } catch (RuntimeException error) {
                overall.setText("配置无效：" + error.getMessage());
                context.showNotification("JLShell Link 配置无效", NotificationLevel.ERROR);
            }
        });
        refresh.setOnAction(event -> subscriptions.refresh().whenComplete((ignored, error) ->
                javafx.application.Platform.runLater(update)));
        trial.setOnAction(event -> subscriptions.claimTrial(MachineFingerprint.current(),
                context.accountSession().snapshot().deviceId()).whenComplete((ignored, error) ->
                javafx.application.Platform.runLater(update)));
        addGateway.setOnAction(event -> {
            String requestedName = gatewayName.getText();
            addGateway.setDisable(true);
            enrollment.clear();
            enrollment.setVisible(false);
            enrollment.setManaged(false);
            subscriptions.requireProgram("link.agent-deploy")
                    .thenCompose(ignored -> v2AccountClient.createEnrollment(requestedName))
                    .whenComplete((created, error) -> javafx.application.Platform.runLater(() -> {
                        addGateway.setDisable(false);
                        if (error != null) {
                            enrollmentState.setText("创建网关注册令牌失败：" + rootMessage(error));
                            return;
                        }
                        String agentId = created.has("agentId") && !created.get("agentId").isJsonNull()
                                ? created.get("agentId").getAsString() : "";
                        String enrollmentToken = created.has("enrollmentToken")
                                && !created.get("enrollmentToken").isJsonNull()
                                ? created.get("enrollmentToken").getAsString() : "";
                        if (agentId.isBlank() || enrollmentToken.isBlank()) {
                            enrollmentState.setText("Website 返回的注册信息不完整，请刷新后重试。");
                            return;
                        }
                        enrollmentState.setText("Agent ID 与一次性令牌仅在这里显示。安装时需要两项；令牌只显示一次，关闭窗口后无法再次读取。");
                        enrollment.setText("Agent ID：" + agentId + "\n一次性注册令牌：\n" + enrollmentToken);
                        enrollment.setVisible(true);
                        enrollment.setManaged(true);
                    }));
        });
        Runnable loadForwardAgents = () -> {
            refreshForwardAgents.setDisable(true);
            forwardStatus.setText("正在读取在线 Java 网关及访问策略…");
            loadForwardAgents().whenComplete((agents, error) -> javafx.application.Platform.runLater(() -> {
                refreshForwardAgents.setDisable(false);
                if (error != null) {
                    forwardAgent.getItems().clear();
                    forwardStatus.setText("无法读取网关目录：" + rootMessage(error));
                    return;
                }
                ForwardAgent selected = forwardAgent.getValue();
                forwardAgent.getItems().setAll(agents);
                forwardAgent.setValue(agents.stream().filter(item -> selected != null
                        && item.agentId().equals(selected.agentId())).findFirst().orElse(null));
                if (agents.isEmpty()) {
                    forwardStatus.setText("没有在线且启用访问策略的 Link v2 网关。可在 Website 完成注册和目标规则配置。");
                } else {
                    forwardStatus.setText("选择网关并输入 Website 策略允许的目标 IP/端口，然后打开一次性本地 TCP 转发。");
                }
            }));
        };
        refreshForwardAgents.setOnAction(event -> loadForwardAgents.run());
        openForward.setOnAction(event -> {
            ForwardAgent selected = forwardAgent.getValue();
            int targetPort;
            try {
                if (selected == null) throw new IllegalArgumentException("请先选择 Java 网关");
                targetPort = Integer.parseInt(forwardTargetPort.getText().trim());
                if (targetPort < 1 || targetPort > 65535) throw new IllegalArgumentException("目标端口超出范围");
                new com.jlshell.link.core.model.TargetEndpoint(forwardTargetIp.getText().trim(), targetPort);
            } catch (RuntimeException invalid) {
                forwardStatus.setText("转发参数无效：" + invalid.getMessage());
                return;
            }
            openForward.setDisable(true);
            forwardStatus.setText("正在向 Website 重新申请目标授权，并按连接策略准备直连或 Relay…");
            subscriptions.requireProgramAndSession("link.tcp-tunnel")
                    .thenCompose(ignored -> linkClientRuntime.openForward(selected.agentId(),
                            forwardTargetIp.getText().trim(), targetPort))
                    .whenComplete((opened, error) -> javafx.application.Platform.runLater(() -> {
                        if (error != null) {
                            openForward.setDisable(false);
                            forwardStatus.setText("本地转发打开失败：" + rootMessage(error));
                            return;
                        }
                        openForwardId.set(opened.id());
                        closeForward.setDisable(false);
                        forwardStatus.setText("本地转发已打开\n本机地址：" + opened.localHost() + ":" + opened.localPort()
                                + "\n目标：" + opened.targetIp() + ":" + opened.targetPort()
                                + "\n路径：" + opened.path() + " · 此转发只接受一个 TCP 连接。");
                    }));
        });
        closeForward.setOnAction(event -> {
            UUID id = openForwardId.getAndSet(null);
            if (id == null) return;
            closeForward.setDisable(true);
            linkClientRuntime.closeForward(id).whenComplete((closed, error) ->
                    javafx.application.Platform.runLater(() -> {
                        openForward.setDisable(false);
                        forwardStatus.setText(error != null ? "关闭本地转发失败：" + rootMessage(error)
                                : Boolean.TRUE.equals(closed) ? "本地转发已关闭。" : "本地转发已自动结束。");
                    }));
        });
        loadForwardAgents.run();
        VBox advanced = new VBox(8, new Label("连接策略"), connectPolicy,
                new Label("AUTO 会并行准备直连与 Relay，先完成安全握手的一条路径胜出；DIRECT_ONLY 和 RELAY_ONLY 用于诊断。"),
                new Label("Link v2 WSS Relay 地址"), relayUri,
                new Label("可选 STUN 服务器（数值 IP:端口，多个用逗号分隔，最多 4 个）"), stunServers,
                save, new Label("客户端节点密钥和 TLS 身份由宿主加密存储管理；STUN 项只保存公开的服务器地址。"));
        advanced.setPadding(new Insets(8));
        TitledPane advancedPane = new TitledPane("高级配置（一般无需修改）", advanced);
        advancedPane.setExpanded(false);

        Label note = new Label("SSH 路由由进程内 Java 客户端建立，每次连接和重连都向 Website 申请新授权。"
                + "AUTO 会同时准备 A—C 直连和 B Relay；网络变化会取消未完成的候选代次，已建立的业务流保留原路径。");
        note.setWrapText(true);
        VBox root = new VBox(10, title, overall, accountState, subscriptionState, runtimeState,
                new HBox(8, trial, refresh), note,
                new Label("添加 Java 网关"), new HBox(8, gatewayName, addGateway), enrollmentState,
                enrollment, new Label("非 SSH TCP 本地转发"),
                new HBox(8, forwardAgent, refreshForwardAgents),
                new HBox(8, forwardTargetIp, forwardTargetPort), new HBox(8, openForward, closeForward),
                forwardStatus, advancedPane);
        root.setPadding(new Insets(12));
        update.run();
        return root;
    }

    boolean settingsDependenciesReady() {
        return linkClientRuntime != null && v2AccountClient != null
                && subscriptions != null && bindingStore != null;
    }

    private Node unavailableSettingsView() {
        Label title = new Label("JLShell Link");
        Label state = new Label("当前插件未完成激活，运行时状态暂不可用。");
        state.setWrapText(true);
        Label guidance = new Label("请先关闭设置窗口并重启 JLShell；如果问题仍然存在，请重新安装完整的 Link 插件发行版并查看日志中的激活失败原因。");
        guidance.setWrapText(true);
        VBox root = new VBox(10, title, state, guidance);
        root.setPadding(new Insets(12));
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

    JsonObject runtimeStatus() {
        JsonObject runtime = linkClientRuntime.status();
        JsonObject account = hostAccountStatus();
        JsonObject subscription = subscriptions.status();
        String state = !"AUTHENTICATED".equals(account.get("state").getAsString()) ? "SIGNED_OUT"
                : !runtime.get("secureStorageAvailable").getAsBoolean() ? "SECURE_STORAGE_UNAVAILABLE"
                : subscription.get("state").getAsString();
        String nextAction = switch (state) {
                    case "READY" -> "OPEN_SESSION";
                    case "TRIAL_AVAILABLE" -> "START_TRIAL_OR_UPGRADE";
                    case "CHECKING" -> "REFRESH_SUBSCRIPTION";
                    case "SIGNED_OUT" -> "LOGIN";
                    case "SECURE_STORAGE_UNAVAILABLE" -> "ENABLE_HOST_SECURE_STORAGE";
                    default -> "CONTACT_ADMIN_OR_UPGRADE";
                };
        JsonObject result = new JsonObject();
        result.addProperty("available", runtime.get("available").getAsBoolean() && "READY".equals(state));
        result.addProperty("state", state);
        result.addProperty("nextAction", nextAction);
        result.addProperty("version", LinkPluginContract.VERSION);
        result.add("runtime", runtime.deepCopy());
        JsonObject retiredConnector = new JsonObject();
        retiredConnector.addProperty("available", false);
        retiredConnector.addProperty("state", "REPLACED_BY_JAVA_CLIENT");
        result.add("connector", retiredConnector);
        result.add("javaClient", runtime.deepCopy());
        result.add("account", account.deepCopy());
        result.add("subscription", subscription.deepCopy());
        return result;
    }

    private JsonObject hostAccountStatus() {
        AccountSession session = context.accountSession().snapshot();
        JsonObject value = new JsonObject();
        value.addProperty("state", session.authenticated() ? "AUTHENTICATED" : "SIGNED_OUT");
        value.addProperty("baseUrl", session.baseUrl() == null ? "" : session.baseUrl());
        return value;
    }

    private static String pathDiagnostic(JsonObject runtime) {
        if (!runtime.has("lastPath") || runtime.get("lastPath").isJsonNull()) {
            return " · 尚无路径记录";
        }
        String path = runtime.get("lastPath").getAsString();
        String outcome = runtime.get("lastPathOutcome").getAsString();
        long elapsed = runtime.get("lastPathElapsedMillis").getAsLong();
        String category = runtime.has("lastFailureCategory") && !runtime.get("lastFailureCategory").isJsonNull()
                ? " · " + runtime.get("lastFailureCategory").getAsString() : "";
        return " · 最近路径 " + path + "/" + outcome + " · " + elapsed + " ms" + category;
    }

    private CompletableFuture<List<ForwardAgent>> loadForwardAgents() {
        return v2AccountClient.agents().thenCompose(agents -> {
            List<CompletableFuture<ForwardAgent>> requests = new ArrayList<>();
            for (com.google.gson.JsonElement item : agents) {
                JsonObject agent = item.getAsJsonObject();
                if (!"ONLINE".equals(jsonString(agent, "state"))
                        || !com.jlshell.link.core.ProtocolVersion.V2.equals(jsonString(agent, "protocolVersion"))
                        || jsonString(agent, "nodeKeyFingerprint").isBlank()) continue;
                UUID id;
                try { id = UUID.fromString(jsonString(agent, "agentId")); }
                catch (RuntimeException invalid) { continue; }
                requests.add(v2AccountClient.accessPolicy(id).thenApply(policy -> {
                    if (!policy.has("enabled") || !policy.get("enabled").getAsBoolean()) return null;
                    return new ForwardAgent(id.toString(), jsonString(agent, "name"),
                            policy.has("version") ? policy.get("version").getAsLong() : 0);
                }));
            }
            CompletableFuture<?>[] pending = requests.toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(pending).thenApply(ignored -> requests.stream()
                    .map(CompletableFuture::join).filter(java.util.Objects::nonNull).toList());
        });
    }

    private static String jsonString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String readinessText(JsonObject runtime, JsonObject account, JsonObject subscriptionStatus) {
        if (!"AUTHENTICATED".equals(account.get("state").getAsString())) {
            return "还差一步：登录 JLShell 账号。Session 会直接复用这里的登录态，不会重复登录。";
        }
        if (!runtime.get("available").getAsBoolean()) {
            return "Java 客户端暂不可用：" + runtime.get("state").getAsString()
                    + "。请确认宿主加密存储可用。";
        }
        String subscription = subscriptionStatus.get("state").getAsString();
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
        return "JLShell Link 已就绪。项目 SSH 连接会通过进程内 Java 客户端建立 WSS Relay 隧道。";
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

    private record ForwardAgent(String agentId, String name, long policyVersion) {
        @Override public String toString() { return name + " · 策略 v" + policyVersion; }
    }

}
