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
 * 会话级只读状态入口。Program 的账号、Relay 和 Agent 配置仍只出现在 Program 设置/项目管理页。
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
                .thenCombine(ProgramCapabilityClient.invoke(context.capabilityBus(), null,
                        LinkPluginContract.ACCOUNT_STATUS_CAPABILITY, new JsonObject()),
                        (runtime, account) -> new StatusSnapshot(runtime.getAsJsonObject(), account.getAsJsonObject()))
                .whenComplete((snapshot, error) -> Platform.runLater(() -> {
                    refresh.setDisable(false);
                    if (error != null) {
                        status.setText("无法读取 Link 状态：" + rootMessage(error)
                                + "。请在 Program 设置中完成账号和 Link 配置。");
                        return;
                    }
                    JsonObject javaClient = snapshot.runtime().has("javaClient")
                            && snapshot.runtime().get("javaClient").isJsonObject()
                                    ? snapshot.runtime().getAsJsonObject("javaClient") : new JsonObject();
                    JsonObject subscription = snapshot.runtime().has("subscription")
                            && snapshot.runtime().get("subscription").isJsonObject()
                                    ? snapshot.runtime().getAsJsonObject("subscription") : new JsonObject();
                    String accountState = snapshot.account().has("state")
                            ? snapshot.account().get("state").getAsString() : "UNKNOWN";
                    String clientState = javaClient.has("state")
                            ? javaClient.get("state").getAsString() : "UNKNOWN";
                    String accessState = subscription.has("state")
                            ? subscription.get("state").getAsString() : "UNKNOWN";
                    int activeTunnels = javaClient.has("openTunnels")
                            ? javaClient.get("openTunnels").getAsInt() : 0;
                    String pathDiagnostic = javaClient.has("lastPath") && !javaClient.get("lastPath").isJsonNull()
                            ? "\n最近路径：" + javaClient.get("lastPath").getAsString() + "/"
                                    + javaClient.get("lastPathOutcome").getAsString() + " · "
                                    + javaClient.get("lastPathElapsedMillis").getAsLong() + " ms"
                                    + (javaClient.has("lastFailureCategory")
                                            && !javaClient.get("lastFailureCategory").isJsonNull()
                                            ? " · " + javaClient.get("lastFailureCategory").getAsString() : "")
                            : "\n尚无路径诊断记录。";
                    status.setText("账号：" + accountState + "\n套餐/策略：" + accessState
                            + "\nJava 客户端：" + clientState + " · 活跃隧道 " + activeTunnels
                            + pathDiagnostic
                            + "\n项目绑定的 SSH 隧道会在建连前打开，当前桌面数据路径使用 WSS Relay。"
                            + "如状态异常，请前往 Website 的 JLShell Link Agent 页面检查 Agent 在线状态和精确目标授权。"
                            + "\nProgram 级配置请在插件设置页管理。");
                }));
    }

    private record StatusSnapshot(JsonObject runtime, JsonObject account) { }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
