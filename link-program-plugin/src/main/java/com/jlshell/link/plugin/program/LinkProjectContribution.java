package com.jlshell.link.plugin.program;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.event.ProjectUpdatedEvent;
import com.jlshell.plugin.api.project.ProjectCreationContext;
import com.jlshell.plugin.api.project.ProjectCreationContribution;
import com.jlshell.plugin.api.project.ProjectManagementContext;
import com.jlshell.plugin.api.storage.PluginStorage;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** 将项目绑定到 Website 中已经注册并在线的 Agent 与精确目标。 */
final class LinkProjectContribution implements ProjectCreationContribution {

    private final PluginStorage storage;
    private final LinkAccountClient account;
    private final ConnectorProcessManager connector;
    private final BundledRuntimeManager runtime;
    private final LinkBindingStore bindings;

    LinkProjectContribution(PluginStorage storage, LinkAccountClient account,
                            ConnectorProcessManager connector, BundledRuntimeManager runtime,
                            LinkBindingStore bindings) {
        this.storage = storage;
        this.account = account;
        this.connector = connector;
        this.runtime = runtime;
        this.bindings = bindings;
    }

    LinkProjectContribution(PluginStorage storage, LinkAccountClient account,
                            ConnectorProcessManager connector, BundledRuntimeManager runtime) {
        this(storage, account, connector, runtime, new LinkBindingStore(storage));
    }

    @Override public String id() { return "jlshell-link-agent"; }
    @Override public int order() { return 200; }

    @Override
    public Node createView(ProjectCreationContext context) {
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, "false");
        context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE, null);
        return view(null, false, null,
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(value)),
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE, value));
    }

    @Override
    public Node createManagementView(ProjectManagementContext context) {
        boolean enabled = enabled(context.projectId());
        JsonObject binding = bindings.getProject(context.projectId());
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(enabled));
        context.putState(LinkPluginContract.PROJECT_AGENT_BINDING_STATE, binding == null ? null : binding.toString());
        return view(context.projectId(), enabled, binding,
                value -> context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(value)),
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

    private Node view(String projectId, boolean initiallyEnabled, JsonObject initialBinding,
                      Consumer<Boolean> enabledUpdate, Consumer<String> bindingUpdate) {
        Label heading = new Label("JLShell Link 网络访问");
        CheckBox enabled = new CheckBox("通过已配置的 Link Agent 访问此项目的内网服务");
        enabled.setSelected(initiallyEnabled);
        Label overall = new Label(); overall.setWrapText(true);
        Label selection = new Label("正在读取账号下的 Agent…"); selection.setWrapText(true);
        ComboBox<ProjectTarget> targets = new ComboBox<>();
        targets.setPromptText("选择此项目使用的 Agent");
        targets.setDisable(!initiallyEnabled);
        Button refresh = new Button("刷新 Agent 列表");
        Button repair = new Button("修复内置运行时");

        Runnable refreshStatus = () -> updateStatus(overall);
        Runnable loadCatalog = () -> {
            refresh.setDisable(true);
            selection.setText("正在读取已注册 Agent 和精确目标…");
            account.catalog().whenComplete((catalog, error) -> Platform.runLater(() -> {
                refresh.setDisable(false);
                if (error != null) {
                    targets.getItems().clear();
                    selection.setText("无法读取 Agent 列表：" + rootMessage(error)
                            + "。请先在 JLShell 账号设置中登录，或前往 Website 注册 Agent。");
                    return;
                }
                List<ProjectTarget> values = targets(catalog.getAsJsonObject());
                targets.getItems().setAll(values);
                ProjectTarget selected = matching(values, initialBinding);
                if (selected == null && !values.isEmpty()) selected = values.getFirst();
                targets.setValue(selected);
                selection.setText(values.isEmpty()
                        ? "账号下没有在线 Agent。请前往 Website 创建注册令牌并完成服务器安装。"
                        : "请选择此项目要使用的 Agent。实际 SSH 主机与端口必须已在 Website 为该 Agent 精确授权。");
            }));
        };

        enabled.selectedProperty().addListener((observable, oldValue, value) -> {
            enabledUpdate.accept(value);
            targets.setDisable(!value);
            if (!value) bindingUpdate.accept(null);
            else if (targets.getValue() != null) bindingUpdate.accept(targets.getValue().json());
        });
        targets.valueProperty().addListener((observable, oldValue, value) -> {
            if (enabled.isSelected()) bindingUpdate.accept(value == null ? null : value.json());
        });
        refresh.setOnAction(event -> loadCatalog.run());
        repair.setOnAction(event -> {
            BundledRuntimeManager.PreparedRuntime prepared = runtime.prepare();
            ConnectorConfiguration.useBundledDefaults(storage);
            connector.configure(ConnectorConfiguration.load(storage, prepared));
            refreshStatus.run();
        });
        refreshStatus.run();
        loadCatalog.run();

        Label guide = new Label("Agent 与节点凭据在 Website 管理；此处只选择当前项目可用的 Agent 和精确目标。"
                + "实际连接仍使用保存的 SSH 主机和端口；如列表为空，请在 Website 的“JLShell Link Agent”页面创建一次性注册令牌并完成服务器安装。");
        guide.setWrapText(true);
        return new VBox(8, heading, enabled, overall, targets, selection,
                new HBox(8, refresh, repair), guide);
    }

    private void updateStatus(Label overall) {
        JsonObject runtimeStatus = runtime.status();
        JsonObject connectorStatus = connector.status();
        JsonObject accountStatus = account.status();
        boolean runtimeReady = runtimeStatus.get("available").getAsBoolean();
        boolean connectorReady = connectorStatus.get("available").getAsBoolean();
        boolean signedIn = "AUTHENTICATED".equals(accountStatus.get("state").getAsString());
        overall.setText(!runtimeReady ? "状态：需要修复插件内置运行时"
                : !connectorReady ? "状态：Connector 尚未就绪"
                : !signedIn ? "状态：请先在 JLShell 账号设置中通过 Web 登录"
                : "状态：已就绪；保存后可为 SSH 连接自动建立 Link 隧道");
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

    private static List<ProjectTarget> targets(JsonObject catalog) {
        List<ProjectTarget> values = new ArrayList<>();
        catalog.getAsJsonArray("agents").forEach(agentEntry -> {
            JsonObject agent = agentEntry.getAsJsonObject();
            if (!"ONLINE".equals(agent.get("state").getAsString())) return;
            boolean hasTarget = agent.getAsJsonArray("targets").asList().stream()
                    .map(JsonElement::getAsJsonObject).anyMatch(target -> target.get("enabled").getAsBoolean());
            if (hasTarget) values.add(new ProjectTarget(agent.get("id").getAsString(), agent.get("name").getAsString()));
        });
        return values;
    }

    private static ProjectTarget matching(List<ProjectTarget> values, JsonObject binding) {
        if (binding == null) return null;
        return values.stream().filter(value -> value.agentId().equals(string(binding, "agentId"))).findFirst().orElse(null);
    }

    private static String string(JsonObject value, String name) {
        return value.has(name) ? value.get(name).getAsString() : "";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ProjectTarget(String agentId, String agentName) {
        String json() {
            JsonObject value = new JsonObject();
            value.addProperty("agentId", agentId);
            return value.toString();
        }
        @Override public String toString() { return agentName; }
    }
}
