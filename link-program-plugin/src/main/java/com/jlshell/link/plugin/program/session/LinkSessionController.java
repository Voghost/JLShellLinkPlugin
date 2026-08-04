package com.jlshell.link.plugin.program.session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.common.ProgramCapabilityClient;
import com.jlshell.link.plugin.common.RuntimeStatusClient;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.session.ProgramSessionController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class LinkSessionController implements ProgramSessionController {

    private PluginContext context;
    private volatile boolean active;
    private final java.util.Set<String> activeTunnelIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    LinkSessionController(PluginContext context) {
        this.context = context;
        active = true;
        context.openTab("JLShell Link", createView(context));
        context.info("JLShell Link session contribution activated");
    }

    @Override
    public void close() {
        if (context == null) {
            return;
        }
        active = false;
        for (String tunnelId : java.util.Set.copyOf(activeTunnelIds)) {
            closeTunnel(context.capabilityBus(), tunnelId);
        }
        activeTunnelIds.clear();
        context = null;
    }

    Node createView(PluginContext context) {
        Label title = new Label("JLShell Link · 当前 SSH 会话");
        Label runtimeResult = new Label("正在检查账号、Connector 和内置运行时…");
        runtimeResult.setWrapText(true);
        Label projectIntent = new Label("正在读取项目的 Agent 设置…");
        projectIntent.setWrapText(true);
        Button startTrial = new Button("领取 14 天 Pro 试用");
        Button refreshAccess = new Button("刷新套餐状态");
        startTrial.setDisable(true);
        refreshAccess.setDisable(true);

        Runnable loadStatus = () -> loadRuntimeStatus(context.capabilityBus()).whenComplete((status, error) ->
                Platform.runLater(() -> {
                    refreshAccess.setDisable(false);
                    runtimeResult.setText(error == null
                            ? readinessText(status.getAsJsonObject())
                            : "无法读取程序级能力：" + rootMessage(error));
                    String state = error == null && status.getAsJsonObject().has("state")
                            ? status.getAsJsonObject().get("state").getAsString() : "UNKNOWN";
                    startTrial.setDisable(!"TRIAL_AVAILABLE".equals(state));
                    refreshAccess.setDisable("SIGNED_OUT".equals(state) || error != null);
                }));

        loadStatus.run();
        refreshAccess.setOnAction(event -> {
            refreshAccess.setDisable(true);
            ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                    LinkPluginContract.SUBSCRIPTION_REFRESH_CAPABILITY, new JsonObject())
                    .whenComplete((value, error) -> {
                        if (error != null) {
                            Platform.runLater(() -> {
                                refreshAccess.setDisable(false);
                                runtimeResult.setText("套餐状态刷新失败：" + rootMessage(error));
                            });
                        } else {
                            loadStatus.run();
                        }
                    });
        });
        startTrial.setOnAction(event -> {
            startTrial.setDisable(true);
            runtimeResult.setText("正在使用当前已验证设备领取试用…");
            ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                    LinkPluginContract.TRIAL_CLAIM_CAPABILITY, new JsonObject())
                    .whenComplete((value, error) -> {
                        if (error != null) {
                            Platform.runLater(() -> {
                                startTrial.setDisable(false);
                                runtimeResult.setText("试用领取失败：" + rootMessage(error));
                            });
                        } else {
                            loadStatus.run();
                        }
                    });
        });
        loadProjectIntent(context.capabilityBus(), sessionId(context)).whenComplete((intent, error) ->
                Platform.runLater(() -> projectIntent.setText(error == null
                        ? (intent.getAsJsonObject().get("requested").getAsBoolean()
                            ? "此项目已启用 Agent 引导，可执行下方安全部署。"
                            : "此项目未预选 Agent；仍可手动检测和部署。")
                        : "无法读取项目 Agent 设置：" + rootMessage(error))));

        VBox deployment = deploymentView(context);
        TitledPane tunnel = new TitledPane("高级：手工查看目录、取票和隧道联调", tunnelView(context));
        tunnel.setExpanded(false);
        Label note = new Label("所有登录、票据和 Connector 均复用 Program 插件全局状态。"
                + "Agent 上传前后都会校验 SHA-256，凭据仅写入受保护的远端临时文件。 ");
        note.setWrapText(true);

        VBox root = new VBox(10, title, runtimeResult, new HBox(8, startTrial, refreshAccess),
                projectIntent, deployment, tunnel, note);
        root.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        return scroll;
    }

    static CompletableFuture<JsonElement> loadRuntimeStatus(CapabilityBus capabilityBus) {
        return RuntimeStatusClient.query(capabilityBus);
    }

    static CompletableFuture<JsonElement> loadProjectIntent(CapabilityBus capabilityBus, String sessionId) {
        JsonObject args = new JsonObject();
        args.addProperty("sessionId", sessionId);
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY, args);
    }

    private VBox deploymentView(PluginContext context) {
        Label heading = new Label("Agent 安装向导");
        Label steps = new Label("1. 检测服务器平台  2. 确认安装位置  3. 上传并校验 Agent  "
                + "4. 注册账号并启动系统服务  5. 绑定当前连接");
        steps.setWrapText(true);
        TextArea result = output("点击“检测当前服务器”开始。不会在确认前写入远端文件。", 6);
        Button detect = new Button("检测当前服务器");
        Button deploy = new Button("安装、注册并绑定当前连接");
        deploy.setDisable(true);
        SshSessionContext ssh = context.sshSession().orElse(null);
        detect.setDisable(ssh == null);
        AtomicReference<RemotePlatform> detected = new AtomicReference<>();

        detect.setOnAction(event -> {
            detect.setDisable(true);
            result.setText("正在通过当前 SSH 会话读取操作系统、CPU 架构和用户目录…");
            AgentDeploymentService service = new AgentDeploymentService(ssh, context.capabilityBus());
            service.detectPlatform().whenComplete((platform, error) -> Platform.runLater(() -> {
                detect.setDisable(false);
                if (error != null) {
                    detected.set(null);
                    deploy.setDisable(true);
                    result.setText("检测失败：" + rootMessage(error)
                            + "\n请确认当前 SSH 用户可执行 uname 或 PowerShell。 ");
                    return;
                }
                detected.set(platform);
                deploy.setDisable(false);
                result.setText("检测完成\n平台：" + platform.platform() + "/" + platform.architecture()
                        + "\n安装路径：" + platform.remoteBinary()
                        + "\n默认授权目标：127.0.0.1:22"
                        + "\n下一步：点击安装按钮并确认。 ");
            }));
        });
        deploy.setOnAction(event -> {
            RemotePlatform platform = detected.get();
            if (platform == null) {
                result.setText("请先检测当前服务器。 ");
                return;
            }
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "将在当前 SSH 服务器执行以下操作：\n"
                            + "• 上传并校验 " + platform.platform() + "/" + platform.architecture() + " Agent\n"
                            + "• 安装到 " + platform.remoteBinary() + "\n"
                            + "• 创建当前用户的后台服务并连接 JLShell Link\n"
                            + "• 仅授权 127.0.0.1:22 并绑定当前连接\n\n是否继续？",
                    ButtonType.OK, ButtonType.CANCEL);
            confirmation.setHeaderText("确认安装 JLShell Link Agent");
            if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
            deploy.setDisable(true);
            detect.setDisable(true);
            result.setText("正在上传、校验、注册并启动 Agent，请勿关闭当前 SSH 会话…");
            new AgentDeploymentService(ssh, context.capabilityBus()).deployAndRegister(ssh.displayName())
                    .whenComplete((deployed, error) -> {
                        if (error != null) {
                            Platform.runLater(() -> {
                                detect.setDisable(false);
                                deploy.setDisable(false);
                                result.setText("安装失败：" + rootMessage(error)
                                        + "\n可重新检测后重试；已上传的临时文件会自动清理。 ");
                                context.showNotification("JLShell Link Agent 安装失败", NotificationLevel.ERROR);
                            });
                            return;
                        }
                        saveBinding(context.capabilityBus(), sessionId(context), deployed)
                                .whenComplete((binding, bindingError) -> Platform.runLater(() -> {
                            detect.setDisable(false);
                            deploy.setDisable(false);
                            result.setText("Agent 已安装并启动\n平台：" + deployed.platform() + "/" + deployed.architecture()
                                    + "\n路径：" + deployed.remotePath() + "\nAgent PeerId："
                                    + deployed.agentPeerId() + "\n授权目标：" + deployed.targetIp() + ":"
                                    + deployed.targetPort()
                                    + "\n服务管理器：" + deployed.serviceManager()
                                    + (bindingError == null ? "\n已绑定当前连接。"
                                    : "\n连接绑定未保存：" + rootMessage(bindingError)));
                            context.showNotification("JLShell Link Agent 已部署并启动", NotificationLevel.INFO);
                        }));
                    });
        });
        return new VBox(7, heading, steps, new VBox(6, detect, deploy), result);
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
            JsonObject bindingArgs = new JsonObject();
            bindingArgs.addProperty("sessionId", sessionId(context));
            ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                    LinkPluginContract.LINK_CATALOG_CAPABILITY, new JsonObject()).thenCombine(
                    ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                            LinkPluginContract.BINDING_GET_CAPABILITY, bindingArgs),
                    (catalog, binding) -> {
                        JsonObject combined = new JsonObject();
                        combined.add("catalog", catalog);
                        combined.add("binding", binding);
                        return (JsonElement) combined;
                    }).whenComplete((value, error) ->
                    Platform.runLater(() -> {
                        loadCatalog.setDisable(false);
                        if (error != null) {
                            result.setText("目录加载失败：" + rootMessage(error));
                            return;
                        }
                        catalogTarget.getItems().clear();
                        catalogRelay.getItems().clear();
                        JsonObject combined = value.getAsJsonObject();
                        JsonObject catalog = combined.getAsJsonObject("catalog");
                        JsonObject bindingResult = combined.getAsJsonObject("binding");
                        JsonObject binding = bindingResult.has("binding")
                                && bindingResult.get("binding").isJsonObject()
                                ? bindingResult.getAsJsonObject("binding") : null;
                        catalog.getAsJsonArray("agents").forEach(agentValue -> {
                            JsonObject agent = agentValue.getAsJsonObject();
                            java.util.List<String> addresses = new java.util.ArrayList<>();
                            if (agent.has("addresses") && agent.get("addresses").isJsonArray()) {
                                agent.getAsJsonArray("addresses").forEach(address ->
                                        addresses.add(address.getAsString()));
                            }
                            agent.getAsJsonArray("targets").forEach(targetValue -> {
                                JsonObject target = targetValue.getAsJsonObject();
                                if (!target.get("enabled").getAsBoolean()) return;
                                catalogTarget.getItems().add(new CatalogTarget(
                                        agent.get("id").getAsString(), agent.get("name").getAsString(),
                                        agent.get("peerId").getAsString(), target.get("targetIp").getAsString(),
                                        target.get("targetPort").getAsInt(), java.util.List.copyOf(addresses)));
                            });
                        });
                        catalog.getAsJsonArray("relays").forEach(relayValue -> {
                            JsonObject relay = relayValue.getAsJsonObject();
                            catalogRelay.getItems().add(new CatalogRelay(relay.get("name").getAsString(),
                                    relay.get("peerId").getAsString(), relay.get("endpoint").getAsString()));
                        });
                        CatalogTarget selected = null;
                        if (binding != null) {
                            selected = catalogTarget.getItems().stream().filter(item ->
                                    item.agentId().equals(binding.get("agentId").getAsString())
                                    && item.targetIp().equals(binding.get("targetIp").getAsString())
                                    && item.targetPort() == binding.get("targetPort").getAsInt())
                                    .findFirst().orElse(null);
                        }
                        if (selected == null && !catalogTarget.getItems().isEmpty()) {
                            selected = catalogTarget.getItems().getFirst();
                        }
                        catalogTarget.setValue(selected);
                        result.setText(binding != null && selected != null
                                ? "账号目录已加载，并已选择当前连接绑定的 Agent。"
                                : "账号目录已加载；请选择目标并自动取票。");
                    }));
        });
        catalogTarget.setOnAction(event -> {
            CatalogTarget selected = catalogTarget.getValue();
            issueTicket.setDisable(selected == null);
            if (selected != null) {
                agentPeer.setText(selected.agentPeer());
                agentAddresses.setText(String.join("\n", selected.addresses()));
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

    private static CompletableFuture<JsonElement> saveBinding(
            CapabilityBus capabilityBus, String sessionId,
            AgentDeploymentService.ProvisioningResult deployed) {
        JsonObject args = new JsonObject();
        args.addProperty("sessionId", sessionId);
        args.addProperty("agentId", deployed.agentId());
        args.addProperty("targetIp", deployed.targetIp());
        args.addProperty("targetPort", deployed.targetPort());
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                LinkPluginContract.BINDING_SAVE_CAPABILITY, args);
    }

    private boolean isActive(PluginContext expected) {
        return active && context == expected;
    }

    private static String readinessText(JsonObject status) {
        String state = status.has("state") ? status.get("state").getAsString() : "UNKNOWN";
        return switch (state) {
            case "READY" -> "状态：已就绪。账号、Connector 和内置运行时可用，可安装或连接 Agent。";
            case "SIGNED_OUT" -> "状态：运行时已就绪，但尚未登录。请在 JLShell 的账号设置中通过 Web 登录后刷新。";
            case "CONNECTOR_NOT_READY" -> "状态：Connector 未就绪。请在插件设置或项目管理中修复内置运行时。";
            case "RUNTIME_MISSING" -> "状态：插件缺少完整原生运行时，请重新安装正式插件包。";
            default -> "状态：" + state + "。请刷新或查看项目管理中的修复建议。";
        };
    }

    private record CatalogTarget(String agentId, String agentName, String agentPeer,
                                 String targetIp, int targetPort, java.util.List<String> addresses) {
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
