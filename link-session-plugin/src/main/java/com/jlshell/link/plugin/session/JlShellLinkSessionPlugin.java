package com.jlshell.link.plugin.session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.common.ProgramCapabilityClient;
import com.jlshell.link.plugin.common.RuntimeStatusClient;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class JlShellLinkSessionPlugin implements JlShellPlugin, PluginView {

    private PluginContext context;
    private volatile boolean active;
    private final java.util.Set<String> activeTunnelIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return LinkPluginContract.SESSION_PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "JLShell Link Session";
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
        return "Displays the global JLShell Link runtime state in an SSH session.";
    }

    @Override
    public boolean requiresSshSession() {
        return true;
    }

    @Override
    public void activate(PluginContext context) {
        this.context = context;
        active = true;
        context.openTab(displayName(), createView(context));
        context.info("JLShell Link session plugin activated");
    }

    @Override
    public void deactivate() {
        if (context == null) {
            return;
        }
        active = false;
        for (String tunnelId : java.util.Set.copyOf(activeTunnelIds)) {
            closeTunnel(context.capabilityBus(), tunnelId);
        }
        activeTunnelIds.clear();
        context.closeTab();
        context = null;
    }

    @Override
    public PluginView view() {
        return this;
    }

    @Override
    public Node createView(PluginContext context) {
        Label title = new Label("JLShell Link Runtime");
        TextArea runtimeResult = output("正在读取程序级能力…", 4);
        Label projectIntent = new Label("正在读取项目的 Agent 设置…");
        projectIntent.setWrapText(true);

        loadRuntimeStatus(context.capabilityBus()).whenComplete((status, error) ->
                Platform.runLater(() -> runtimeResult.setText(error == null
                        ? new GsonBuilder().setPrettyPrinting().create().toJson(status)
                        : "无法读取程序级能力：" + rootMessage(error))));
        loadProjectIntent(context.capabilityBus(), sessionId(context)).whenComplete((intent, error) ->
                Platform.runLater(() -> projectIntent.setText(error == null
                        ? (intent.getAsJsonObject().get("requested").getAsBoolean()
                            ? "此项目已启用 Agent 引导，可执行下方安全部署。"
                            : "此项目未预选 Agent；仍可手动检测和部署。")
                        : "无法读取项目 Agent 设置：" + rootMessage(error))));

        VBox deployment = deploymentView(context);
        TitledPane tunnel = new TitledPane("打开 SSH/TCP 隧道（开发联调）", tunnelView(context));
        tunnel.setExpanded(false);
        Label note = new Label("当前支持从本地已校验发布目录部署 Linux x64、macOS ARM64 和 "
                + "Windows x64 Agent，并可在桌面 PKCE 登录后从账号目录自动签发短期票据。");
        note.setWrapText(true);

        VBox root = new VBox(10, title, runtimeResult, projectIntent, deployment, tunnel, note);
        root.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        return scroll;
    }

    CompletableFuture<JsonElement> loadRuntimeStatus(CapabilityBus capabilityBus) {
        return RuntimeStatusClient.query(capabilityBus);
    }

    CompletableFuture<JsonElement> loadProjectIntent(CapabilityBus capabilityBus, String sessionId) {
        JsonObject args = new JsonObject();
        args.addProperty("sessionId", sessionId);
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY, args);
    }

    private VBox deploymentView(PluginContext context) {
        Label heading = new Label("Agent 自动部署");
        TextArea result = output("尚未检测远端平台。", 4);
        Button deploy = new Button("部署、注册并启动 Agent");
        SshSessionContext ssh = context.sshSession().orElse(null);
        deploy.setDisable(ssh == null);
        deploy.setOnAction(event -> {
            deploy.setDisable(true);
            result.setText("正在检测远端平台、校验本地发布物并上传…");
            new AgentDeploymentService(ssh, context.capabilityBus()).deployAndRegister(ssh.displayName())
                    .whenComplete((deployed, error) ->
                    Platform.runLater(() -> {
                        deploy.setDisable(false);
                        if (error != null) {
                            result.setText("部署失败：" + rootMessage(error));
                            context.showNotification("JLShell Link Agent 部署失败", NotificationLevel.ERROR);
                        } else {
                            result.setText("部署并注册完成\n平台：" + deployed.platform() + "/" + deployed.architecture()
                                    + "\n路径：" + deployed.remotePath() + "\nAgent PeerId："
                                    + deployed.agentPeerId() + "\n授权目标：" + deployed.targetIp() + ":"
                                    + deployed.targetPort());
                            context.showNotification("JLShell Link Agent 已部署并启动", NotificationLevel.INFO);
                        }
                    }));
        });
        return new VBox(6, heading, deploy, result);
    }

    private Node tunnelView(PluginContext context) {
        TextField agentPeer = new TextField();
        agentPeer.setPromptText("Agent PeerId");
        TextArea agentAddresses = output("", 3);
        agentAddresses.setEditable(true);
        agentAddresses.setPromptText("每行一个 /ip4 或 /ip6 multiaddr");
        TextField relayAddress = new TextField();
        relayAddress.setPromptText("可选 Relay IP multiaddr");
        TextField relayPeer = new TextField();
        relayPeer.setPromptText("可选 Relay PeerId");
        ComboBox<String> policy = new ComboBox<>();
        policy.getItems().addAll("auto", "direct-only", "relay-only");
        policy.setValue("auto");
        TextField targetIp = new TextField("127.0.0.1");
        TextField targetPort = new TextField("22");
        TextArea ticket = output("", 4);
        ticket.setEditable(true);
        ticket.setPromptText("网站签发的 base64url 票据");
        TextArea result = output("尚未打开隧道。", 4);
        Button open = new Button("打开本地隧道");
        Button loadCatalog = new Button("加载账号 Agent / 目标");
        ComboBox<CatalogTarget> catalogTarget = new ComboBox<>();
        catalogTarget.setPromptText("选择已注册 Agent 和精确目标");
        ComboBox<CatalogRelay> catalogRelay = new ComboBox<>();
        catalogRelay.setPromptText("可选网站 Relay");
        Button issueTicket = new Button("自动签发短期票据");
        issueTicket.setDisable(true);
        Button close = new Button("关闭隧道");
        close.setDisable(true);
        AtomicReference<String> tunnelId = new AtomicReference<>();
        loadCatalog.setOnAction(event -> {
            loadCatalog.setDisable(true);
            result.setText("正在读取账号 Agent、目标和 Relay…");
            ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                    LinkPluginContract.LINK_CATALOG_CAPABILITY, new JsonObject()).whenComplete((value, error) ->
                    Platform.runLater(() -> {
                        loadCatalog.setDisable(false);
                        if (error != null) {
                            result.setText("目录加载失败：" + rootMessage(error));
                            return;
                        }
                        catalogTarget.getItems().clear();
                        catalogRelay.getItems().clear();
                        JsonObject catalog = value.getAsJsonObject();
                        catalog.getAsJsonArray("agents").forEach(agentValue -> {
                            JsonObject agent = agentValue.getAsJsonObject();
                            agent.getAsJsonArray("targets").forEach(targetValue -> {
                                JsonObject target = targetValue.getAsJsonObject();
                                if (!target.get("enabled").getAsBoolean()) return;
                                catalogTarget.getItems().add(new CatalogTarget(
                                        agent.get("id").getAsString(), agent.get("name").getAsString(),
                                        agent.get("peerId").getAsString(), target.get("targetIp").getAsString(),
                                        target.get("targetPort").getAsInt()));
                            });
                        });
                        catalog.getAsJsonArray("relays").forEach(relayValue -> {
                            JsonObject relay = relayValue.getAsJsonObject();
                            catalogRelay.getItems().add(new CatalogRelay(relay.get("name").getAsString(),
                                    relay.get("peerId").getAsString(), relay.get("endpoint").getAsString()));
                        });
                        if (!catalogTarget.getItems().isEmpty()) catalogTarget.setValue(catalogTarget.getItems().getFirst());
                        result.setText("账号目录已加载；请选择目标并自动取票。");
                    }));
        });
        catalogTarget.setOnAction(event -> {
            CatalogTarget selected = catalogTarget.getValue();
            issueTicket.setDisable(selected == null);
            if (selected != null) {
                agentPeer.setText(selected.agentPeer());
                targetIp.setText(selected.targetIp());
                targetPort.setText(Integer.toString(selected.targetPort()));
            }
        });
        catalogRelay.setOnAction(event -> {
            CatalogRelay selected = catalogRelay.getValue();
            if (selected != null) {
                relayPeer.setText(selected.peerId());
                relayAddress.setText(selected.endpoint());
            }
        });
        issueTicket.setOnAction(event -> {
            CatalogTarget selected = catalogTarget.getValue();
            if (selected == null) return;
            JsonObject args = new JsonObject();
            args.addProperty("agentId", selected.agentId());
            args.addProperty("targetIp", selected.targetIp());
            args.addProperty("targetPort", selected.targetPort());
            issueTicket.setDisable(true);
            ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                    LinkPluginContract.TICKET_ISSUE_CAPABILITY, args).whenComplete((value, error) ->
                    Platform.runLater(() -> {
                        issueTicket.setDisable(false);
                        if (error != null) result.setText("票据签发失败：" + rootMessage(error));
                        else {
                            ticket.setText(value.getAsJsonObject().get("ticket").getAsString());
                            result.setText("短期单流票据已签发，可立即打开隧道。");
                        }
                    }));
        });
        open.setOnAction(event -> {
            JsonObject args;
            try {
                args = tunnelArgs(agentPeer.getText(), agentAddresses.getText(), relayAddress.getText(),
                        relayPeer.getText(), policy.getValue(), ticket.getText(),
                        targetIp.getText(), targetPort.getText());
            } catch (RuntimeException error) {
                result.setText("参数无效：" + error.getMessage());
                return;
            }
            open.setDisable(true);
            result.setText("正在建立直连或 Relay 回退链路…");
            ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                    LinkPluginContract.TUNNEL_OPEN_CAPABILITY, args).whenComplete((value, error) -> {
                if (error == null && !isActive(context)) {
                    closeTunnel(context.capabilityBus(), value.getAsJsonObject().get("tunnelId").getAsString());
                    return;
                }
                Platform.runLater(() -> {
                        if (!isActive(context)) {
                            return;
                        }
                        open.setDisable(false);
                        if (error != null) {
                            result.setText("隧道打开失败：" + rootMessage(error));
                            return;
                        }
                        JsonObject opened = value.getAsJsonObject();
                        tunnelId.set(opened.get("tunnelId").getAsString());
                        activeTunnelIds.add(tunnelId.get());
                        close.setDisable(false);
                        result.setText("本地地址：" + opened.get("localAddress").getAsString()
                                + "\n连接路径：" + opened.get("connectionPath").getAsString());
                    });
            });
        });
        close.setOnAction(event -> {
            String id = tunnelId.getAndSet(null);
            if (id == null) {
                return;
            }
            activeTunnelIds.remove(id);
            close.setDisable(true);
            closeTunnel(context.capabilityBus(), id).whenComplete((value, error) ->
                    Platform.runLater(() -> result.setText(error == null
                            ? "隧道已关闭。" : "关闭失败：" + rootMessage(error))));
        });
        VBox content = new VBox(6,
                new Label("Agent PeerId"), agentPeer,
                loadCatalog, new Label("账号 Agent / 目标"), catalogTarget,
                new Label("网站 Relay"), catalogRelay, issueTicket,
                new Label("Agent 地址"), agentAddresses,
                new Label("Relay 地址"), relayAddress,
                new Label("Relay PeerId"), relayPeer,
                new Label("连接策略"), policy,
                new Label("目标 IP"), targetIp,
                new Label("目标端口"), targetPort,
                new Label("签名票据"), ticket,
                open, close, result);
        content.setPadding(new Insets(8));
        return content;
    }

    static JsonObject tunnelArgs(String agentPeer, String addresses, String relayAddress,
                                 String relayPeer, String policy, String ticket,
                                 String targetIp, String targetPort) {
        JsonObject args = new JsonObject();
        args.addProperty("agentPeer", required(agentPeer, "Agent PeerId"));
        var values = new com.google.gson.JsonArray();
        java.util.Arrays.stream(addresses == null ? new String[0] : addresses.split("[\\s,]+"))
                .map(String::trim).filter(value -> !value.isEmpty()).forEach(values::add);
        args.add("agentAddresses", values);
        putOptional(args, "relayAddress", relayAddress);
        putOptional(args, "relayPeer", relayPeer);
        args.addProperty("connectPolicy", required(policy, "连接策略"));
        args.addProperty("ticket", required(ticket, "签名票据"));
        args.addProperty("targetIp", required(targetIp, "目标 IP"));
        try {
            args.addProperty("targetPort", Integer.parseInt(required(targetPort, "目标端口")));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("目标端口必须是数字", error);
        }
        return args;
    }

    private static TextArea output(String value, int rows) {
        TextArea result = new TextArea(value);
        result.setEditable(false);
        result.setWrapText(true);
        result.setPrefRowCount(rows);
        VBox.setVgrow(result, Priority.NEVER);
        return result;
    }

    private static String sessionId(PluginContext context) {
        return context.sshSession().map(SshSessionContext::sessionId).orElse("");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value.trim();
    }

    private static void putOptional(JsonObject target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.addProperty(name, value.trim());
        }
    }

    private static CompletableFuture<JsonElement> closeTunnel(CapabilityBus capabilityBus, String tunnelId) {
        JsonObject args = new JsonObject();
        args.addProperty("tunnelId", tunnelId);
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                LinkPluginContract.TUNNEL_CLOSE_CAPABILITY, args);
    }

    private boolean isActive(PluginContext expected) {
        return active && context == expected;
    }

    private record CatalogTarget(String agentId, String agentName, String agentPeer,
                                 String targetIp, int targetPort) {
        @Override public String toString() {
            return agentName + " · " + targetIp + ":" + targetPort;
        }
    }

    private record CatalogRelay(String name, String peerId, String endpoint) {
        @Override public String toString() { return name + " · " + endpoint; }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
