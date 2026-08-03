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

        Button login = new Button("登录账号");
        Button trial = new Button("领取 14 天 Pro 试用");
        Button repair = new Button("修复内置运行时");
        Button refresh = new Button("刷新状态");
        Runnable refreshStatus = () -> updateStatus(overall, details, login, trial);

        login.setOnAction(event -> account.startLogin().whenComplete((value, error) ->
                Platform.runLater(() -> {
                    refreshStatus.run();
                    if (error != null) overall.setText("登录失败：" + rootMessage(error));
                    else overall.setText("浏览器登录已打开，完成后点击刷新状态。");
                })));
        repair.setOnAction(event -> {
            BundledRuntimeManager.PreparedRuntime prepared = runtime.prepare();
            ConnectorConfiguration.useBundledDefaults(storage);
            connector.configure(ConnectorConfiguration.load(storage, prepared));
            refreshStatus.run();
        });
        trial.setOnAction(event -> {
            trial.setDisable(true);
            account.claimTrial().whenComplete((value, error) -> Platform.runLater(() -> {
                refreshStatus.run();
                overall.setText(error == null
                        ? "状态：14 天 Pro 试用已开通，可安装或连接 Agent"
                        : "试用领取失败：" + rootMessage(error));
            }));
        });
        refresh.setOnAction(event -> {
            refresh.setDisable(true);
            account.refreshSubscription().whenComplete((value, error) -> Platform.runLater(() -> {
                refresh.setDisable(false);
                refreshStatus.run();
                if (error != null) overall.setText("套餐状态刷新失败：" + rootMessage(error));
            }));
        });
        refreshStatus.run();
        if ("AUTHENTICATED".equals(account.status().get("state").getAsString())) {
            account.refreshSubscription().whenComplete((value, error) -> Platform.runLater(refreshStatus));
        }

        return new VBox(7, heading, requested, overall, details,
                new HBox(8, login, trial, repair, refresh), guide);
    }

    private void updateStatus(Label overall, Label details, Button login, Button trial) {
        JsonObject runtimeStatus = runtime.status();
        JsonObject connectorStatus = connector.status();
        JsonObject accountStatus = account.status();
        boolean runtimeReady = runtimeStatus.get("available").getAsBoolean();
        boolean connectorReady = connectorStatus.get("available").getAsBoolean();
        boolean signedIn = "AUTHENTICATED".equals(accountStatus.get("state").getAsString());
        JsonObject subscription = accountStatus.getAsJsonObject("subscription");
        String access = subscription.get("state").getAsString();
        overall.setText(!runtimeReady ? "状态：需要重新安装或修复完整插件运行时"
                : !connectorReady ? "状态：Connector 尚未就绪"
                : !signedIn ? "状态：运行时已就绪，请登录 JLShell 账号"
                : switch (access) {
                    case "READY" -> "状态：已就绪，可在项目 SSH 会话中安装或连接 Agent";
                    case "TRIAL_AVAILABLE" -> "状态：当前套餐不含 Link，可领取 14 天 Pro 试用";
                    case "UPGRADE_REQUIRED" -> "状态：需要 Plus 或 Pro 套餐";
                    case "DISABLED_BY_ADMIN" -> "状态：管理员已停用 JLShell Link";
                    case "VERSION_NOT_SUPPORTED" -> "状态：当前插件版本不在管理员允许范围内";
                    case "CHECK_FAILED" -> "状态：套餐检查失败，请检查网络或网站服务";
                    default -> "状态：正在检查套餐与插件策略";
                });
        details.setText("账号 " + accountStatus.get("state").getAsString()
                + " · 运行时 " + runtimeStatus.get("state").getAsString()
                + " · Connector " + connectorStatus.get("state").getAsString()
                + " · 套餐 " + plan(subscription) + " · 权限 " + access);
        login.setDisable(signedIn || !connectorReady);
        trial.setDisable(!signedIn || !"TRIAL_AVAILABLE".equals(access));
    }

    private static String plan(JsonObject subscription) {
        return subscription.has("entitlement") && subscription.get("entitlement").isJsonObject()
                ? subscription.getAsJsonObject("entitlement").get("plan").getAsString() : "未知";
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
