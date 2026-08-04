package com.jlshell.link.plugin.program;

import java.util.function.Consumer;

import com.google.gson.JsonObject;
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
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** 在新建和已有项目中展示同一套 Link 状态、配置与修复入口。 */
final class LinkProjectContribution implements ProjectCreationContribution {

    private final PluginStorage storage;
    private final LinkAccountClient account;
    private final ConnectorProcessManager connector;
    private final BundledRuntimeManager runtime;

    LinkProjectContribution(PluginStorage storage, LinkAccountClient account,
                            ConnectorProcessManager connector, BundledRuntimeManager runtime) {
        this.storage = storage;
        this.account = account;
        this.connector = connector;
        this.runtime = runtime;
    }

    @Override
    public String id() {
        return "jlshell-link-agent";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public Node createView(ProjectCreationContext context) {
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, "true");
        return view(true, value -> context.putState(
                LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(value)));
    }

    @Override
    public Node createManagementView(ProjectManagementContext context) {
        boolean enabled = enabled(context.projectId());
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(enabled));
        return view(enabled, value -> context.putState(
                LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, Boolean.toString(value)));
    }

    @Override
    public void onProjectCreated(ProjectCreatedEvent event, ProjectCreationContext context) {
        save(event.projectId(), context.state(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE));
    }

    @Override
    public void onProjectUpdated(ProjectUpdatedEvent event, ProjectManagementContext context) {
        save(event.projectId(), context.state(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE));
    }

    private Node view(boolean enabled, Consumer<Boolean> updateState) {
        Label heading = new Label("JLShell Link");
        CheckBox requested = new CheckBox("为此项目启用加密远程访问和 Agent 安装引导");
        requested.setSelected(enabled);
        requested.selectedProperty().addListener((observable, oldValue, newValue) ->
                updateState.accept(newValue));

        Label overall = new Label();
        overall.setWrapText(true);
        Label details = new Label();
        details.setWrapText(true);
        Label guide = new Label("使用步骤：保存项目 → 打开项目中的 SSH 会话 → 进入 JLShell Link 页签 → "
                + "检测服务器 → 确认安装。Agent 默认授权远端 127.0.0.1:22，安装前会显示平台、路径和服务方式。\n"
                + "Session 直接复用 Program 插件登录态，无需再次登录或填写票据。 ");
        guide.setWrapText(true);

        Button repair = new Button("修复内置运行时");
        Button refresh = new Button("刷新状态");
        Runnable refreshStatus = () -> updateStatus(overall, details);
        repair.setOnAction(event -> {
            BundledRuntimeManager.PreparedRuntime prepared = runtime.prepare();
            ConnectorConfiguration.useBundledDefaults(storage);
            connector.configure(ConnectorConfiguration.load(storage, prepared));
            refreshStatus.run();
        });
        refresh.setOnAction(event -> refreshStatus.run());
        refreshStatus.run();

        return new VBox(7, heading, requested, overall, details,
                new HBox(8, repair, refresh), guide);
    }

    private void updateStatus(Label overall, Label details) {
        JsonObject runtimeStatus = runtime.status();
        JsonObject connectorStatus = connector.status();
        JsonObject accountStatus = account.status();
        boolean runtimeReady = runtimeStatus.get("available").getAsBoolean();
        boolean connectorReady = connectorStatus.get("available").getAsBoolean();
        boolean signedIn = "AUTHENTICATED".equals(accountStatus.get("state").getAsString());
        overall.setText(!runtimeReady ? "状态：需要重新安装或修复完整插件运行时"
                : !connectorReady ? "状态：Connector 尚未就绪"
                : !signedIn ? "状态：运行时已就绪，请在 JLShell 的账号设置中通过 Web 登录"
                : "状态：已就绪，可在项目 SSH 会话中安装或连接 Agent");
        details.setText("账号 " + accountStatus.get("state").getAsString()
                + " · 运行时 " + runtimeStatus.get("state").getAsString()
                + " · Connector " + connectorStatus.get("state").getAsString());
    }

    private boolean enabled(String projectId) {
        return storage != null && Boolean.parseBoolean(storage.get(projectKey(projectId), "false"));
    }

    private void save(String projectId, String value) {
        if (storage != null) storage.put(projectKey(projectId), Boolean.toString(Boolean.parseBoolean(value)));
    }

    static String projectKey(String projectId) {
        return "project." + projectId + ".agent-requested";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
