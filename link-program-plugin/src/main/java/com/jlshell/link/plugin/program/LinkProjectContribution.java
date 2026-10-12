package com.jlshell.link.plugin.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.link.core.ProtocolVersion;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.event.ProjectUpdatedEvent;
import com.jlshell.plugin.api.project.ProjectCreationContext;
import com.jlshell.plugin.api.project.ProjectCreationContribution;
import com.jlshell.plugin.api.project.ProjectManagementContext;
import com.jlshell.plugin.api.storage.PluginStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** 将项目绑定到 Website 中已经注册并在线的 Java Link Agent。 */
final class LinkProjectContribution implements ProjectCreationContribution {

    private final PluginStorage storage;
    private final LinkV2AccountClient account;
    private final LinkSubscriptionService subscriptions;
    private final LinkClientRuntime client;
    private final LinkBindingStore bindings;

    LinkProjectContribution(PluginStorage storage, LinkV2AccountClient account,
            LinkSubscriptionService subscriptions, LinkClientRuntime client, LinkBindingStore bindings) {
        this.storage = storage;
        this.account = account;
        this.subscriptions = subscriptions;
        this.client = client;
        this.bindings = bindings;
    }

    /** Keeps project-state persistence usable by older callers and storage-focused tests. */
    LinkProjectContribution(PluginStorage storage, Object ignoredLegacyAccount,
            Object ignoredLegacyConnector, Object ignoredLegacyRuntime) {
        this(storage, (LinkV2AccountClient) null, null, null, new LinkBindingStore(storage));
    }

    @Override public String id() { return "jlshell-link-agent"; }
    @Override public int order() { return 200; }

    @Override
    public Node createView(ProjectCreationContext context) {
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, "false");
        context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE, null);
        return view(false, null,
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE,
                        Boolean.toString(value)),
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE, value));
    }

    @Override
    public Node createManagementView(ProjectManagementContext context) {
        boolean enabled = enabled(context.projectId());
        JsonObject binding = bindings.getProject(context.projectId());
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(enabled));
        context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE,
                binding == null ? null : binding.toString());
        return view(enabled, binding,
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE,
                        Boolean.toString(value)),
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE, value));
    }

    @Override
    public void onProjectCreated(ProjectCreatedEvent event, ProjectCreationContext context) {
        save(event.projectId(), context.state(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE),
                context.state(LinkPluginContract.PROJECT_AGENT_BINDING_STATE));
    }

    @Override
    public void onProjectUpdated(ProjectUpdatedEvent event, ProjectManagementContext context) {
        save(event.projectId(), context.state(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE),
                context.state(LinkPluginContract.PROJECT_AGENT_BINDING_STATE));
    }

    private Node view(boolean initiallyEnabled, JsonObject initialBinding,
            Consumer<Boolean> enabledUpdate, Consumer<String> bindingUpdate) {
        Label heading = new Label("JLShell Link 网络访问");
        CheckBox enabled = new CheckBox("通过 Java Link Agent 访问此项目的内网服务");
        enabled.setSelected(initiallyEnabled);
        Label overall = new Label();
        overall.setWrapText(true);
        Label selection = new Label("正在读取在线 Java Link Agent…");
        selection.setWrapText(true);
        ComboBox<ProjectTarget> targets = new ComboBox<>();
        targets.setPromptText("选择此项目使用的网关");
        targets.setDisable(!initiallyEnabled);
        Button refresh = new Button("刷新网关列表");
        AtomicBoolean updatingCatalog = new AtomicBoolean();
        AtomicReference<JsonObject> currentBinding = new AtomicReference<>(initialBinding);

        Runnable refreshStatus = () -> updateStatus(overall);
        Runnable loadCatalog = () -> {
            refresh.setDisable(true);
            selection.setText("正在检查账号权限、网关状态和访问策略…");
            loadTargets().whenComplete((values, error) -> Platform.runLater(() -> {
                refresh.setDisable(false);
                if (error != null) {
                    targets.getItems().clear();
                    selection.setText("无法读取 Java Agent 目录：" + rootMessage(error)
                            + "。请确认 JLShell 已登录，并在 Website 完成网关注册和访问策略配置。");
                    return;
                }
                ProjectTarget selected = matching(values, currentBinding.get());
                updatingCatalog.set(true);
                try {
                    targets.getItems().setAll(values);
                    targets.setValue(selected);
                } finally {
                    updatingCatalog.set(false);
                }
                selection.setText(currentBinding.get() != null && selected == null
                        ? "原项目绑定的网关目前不可用；原绑定已保留。请检查网关在线状态、Link v2 版本和访问策略。"
                        : values.isEmpty()
                                ? "没有在线且已绑定安全身份的 Link v2 网关。请先在 Program 设置中创建注册令牌，再按 Website 安装说明部署 Java Agent。"
                                : "每次 SSH 建连都会重新向 Website 申请目标授权；最终目标必须命中该网关当前的访问策略。");
            }));
        };

        enabled.selectedProperty().addListener((observable, oldValue, value) -> {
            enabledUpdate.accept(value);
            targets.setDisable(!value);
            if (!value) {
                currentBinding.set(null);
                bindingUpdate.accept(null);
            } else if (targets.getValue() != null) {
                bindingUpdate.accept(targets.getValue().json());
            }
        });
        targets.valueProperty().addListener((observable, oldValue, value) -> {
            if (enabled.isSelected() && !updatingCatalog.get() && value != null) {
                currentBinding.set(value.binding());
                bindingUpdate.accept(value.json());
            }
        });
        refresh.setOnAction(event -> loadCatalog.run());
        refreshStatus.run();
        loadCatalog.run();

        Label guide = new Label("项目只保存网关 agentId，不保存票据或登录凭据。SSH 主机和端口仍是实际目标；"
                + "访问许可在每次连接时由 Website 按最新策略重新签发。");
        guide.setWrapText(true);
        return new VBox(8, heading, enabled, overall, targets, selection, new HBox(8, refresh), guide);
    }

    private CompletableFuture<List<ProjectTarget>> loadTargets() {
        if (account == null || subscriptions == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Java Link 客户端尚未初始化"));
        }
        return subscriptions.requireProgramAndSession("link.tcp-tunnel")
                .thenCompose(ignored -> account.agents())
                .thenCompose(this::loadTargetPolicies);
    }

    private CompletableFuture<List<ProjectTarget>> loadTargetPolicies(JsonArray agents) {
        List<CompletableFuture<ProjectTarget>> requests = new ArrayList<>();
        for (JsonElement item : agents) {
            JsonObject agent = item.getAsJsonObject();
            if (!"ONLINE".equals(string(agent, "state"))
                    || !ProtocolVersion.V2.equals(string(agent, "protocolVersion"))
                    || string(agent, "nodeKeyFingerprint").isBlank()) {
                continue;
            }
            java.util.UUID id;
            try { id = java.util.UUID.fromString(string(agent, "agentId")); }
            catch (RuntimeException invalid) { continue; }
            requests.add(account.accessPolicy(id).thenApply(policy -> {
                boolean enabled = policy.has("enabled") && policy.get("enabled").getAsBoolean();
                JsonArray rules = policy.has("rules") && policy.get("rules").isJsonArray()
                        ? policy.getAsJsonArray("rules") : new JsonArray();
                return new ProjectTarget(id.toString(), string(agent, "name"),
                        string(agent, "nodeKeyFingerprint"), string(agent, "protocolVersion"),
                        policy.has("version") ? policy.get("version").getAsLong() : 0,
                        enabled, rules.size());
            }));
        }
        CompletableFuture<?>[] pending = requests.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending).thenApply(ignored -> requests.stream()
                .map(CompletableFuture::join).toList());
    }

    private void updateStatus(Label overall) {
        if (client == null || subscriptions == null) {
            overall.setText("状态：Java Link 客户端尚未初始化。");
            return;
        }
        JsonObject runtime = client.status();
        JsonObject subscription = subscriptions.status();
        String linkState = runtime.get("state").getAsString();
        String accessState = subscription.get("state").getAsString();
        overall.setText(!runtime.get("available").getAsBoolean()
                ? "状态：" + linkState + "。请在 JLShell 中登录并启用宿主加密存储。"
                : !"READY".equals(accessState)
                        ? "状态：账号或套餐暂不可用（" + accessState + "）。"
                        : "状态：Java 客户端已就绪；连接时会重新申请授权并通过 WSS Relay 建立隧道。");
    }

    private void save(String projectId, String enabled, String binding) {
        boolean requested = Boolean.parseBoolean(enabled);
        if (storage != null) storage.put(projectKey(projectId), Boolean.toString(requested));
        bindings.saveProject(projectId, requested ? parse(binding) : null);
    }

    private boolean enabled(String projectId) {
        return storage != null && Boolean.parseBoolean(storage.get(projectKey(projectId), "false"));
    }

    static String projectKey(String projectId) { return "project." + projectId + ".agent-requested"; }

    private static JsonObject parse(String value) {
        if (value == null) return null;
        try { return JsonParser.parseString(value).getAsJsonObject(); }
        catch (RuntimeException error) { return null; }
    }

    private static ProjectTarget matching(List<ProjectTarget> values, JsonObject binding) {
        if (binding == null) return null;
        return values.stream().filter(value -> value.agentId().equals(string(binding, "agentId")))
                .findFirst().orElse(null);
    }

    private static String string(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : "";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while ((current.getCause() != null) && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ProjectTarget(String agentId, String agentName, String fingerprint,
            String protocolVersion, long policyVersion, boolean policyEnabled, int ruleCount) {
        JsonObject binding() {
            JsonObject value = new JsonObject();
            value.addProperty("agentId", agentId);
            return value;
        }
        String json() { return binding().toString(); }
        @Override public String toString() {
            String shortFingerprint = fingerprint.length() <= 12
                    ? fingerprint : fingerprint.substring(fingerprint.length() - 12);
            return agentName + " · " + protocolVersion + " · key …" + shortFingerprint
                    + " · 策略 v" + policyVersion + (policyEnabled ? "（" + ruleCount + " 条规则）" : "（未启用）");
        }
    }
}
