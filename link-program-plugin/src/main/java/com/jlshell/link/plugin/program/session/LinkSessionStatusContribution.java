package com.jlshell.link.plugin.program.session;

import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.common.ProgramCapabilityClient;
import com.jlshell.link.plugin.common.RuntimeStatusClient;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.session.ProgramSessionContribution;
import com.jlshell.plugin.api.session.ProgramSessionController;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 会话级只读状态入口。Program 的账号、Connector 和 Agent 配置仍只出现在 Program 设置/项目管理页。
 */
public final class LinkSessionStatusContribution implements ProgramSessionContribution {

    @Override public String displayName() { return "JLShell Link"; }

    @Override
    public String description() {
        return "查看当前 SSH 连接使用的 Link Agent 状态。";
    }

    @Override
    public ProgramSessionController activate(com.jlshell.plugin.api.PluginContext context) {
        context.openTab("JLShell Link", createView(context));
        return ProgramSessionController.noop();
    }

    private Node createView(PluginContext context) {
        Label title = new Label("JLShell Link · 当前 SSH 会话");
        Label status = new Label("正在读取 Link 状态…");
        status.setWrapText(true);
        Button refresh = new Button("刷新状态");
        refresh.setOnAction(event -> load(context, status, refresh));
        load(context, status, refresh);
        return new VBox(8, title, status, refresh);
    }

    private void load(PluginContext context, Label status, Button refresh) {
        refresh.setDisable(true);
        RuntimeStatusClient.query(context.capabilityBus())
                .thenCompose(runtime -> ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                        LinkPluginContract.ACCOUNT_STATUS_CAPABILITY, new JsonObject()))
                .whenComplete((account, error) -> Platform.runLater(() -> {
                    refresh.setDisable(false);
                    if (error != null) {
                        status.setText("无法读取 Link 状态：" + rootMessage(error)
                                + "。请在 Program 设置中完成账号和 Agent 配置。");
                        return;
                    }
                    JsonObject value = account.getAsJsonObject();
                    String accountState = value.has("state") ? value.get("state").getAsString() : "UNKNOWN";
                    status.setText("账号：" + accountState
                            + "\n当前 SSH 隧道由项目绑定的 Agent 在建连前建立。"
                            + "如状态异常，请前往 Website 的 JLShell Link Agent 页面检查 Agent 在线状态和精确目标授权。"
                            + "\nProgram 级配置请在插件设置页管理。");
                }));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
