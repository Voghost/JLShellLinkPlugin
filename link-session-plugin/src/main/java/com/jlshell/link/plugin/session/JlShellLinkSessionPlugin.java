package com.jlshell.link.plugin.session;

import java.util.concurrent.CompletableFuture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.common.RuntimeStatusClient;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class JlShellLinkSessionPlugin implements JlShellPlugin, PluginView {

    private PluginContext context;

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
        context.openTab(displayName(), createView(context));
        context.info("JLShell Link session plugin activated");
    }

    @Override
    public void deactivate() {
        if (context == null) {
            return;
        }
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
        TextArea result = new TextArea("正在读取程序级能力…");
        result.setEditable(false);
        result.setWrapText(true);
        VBox.setVgrow(result, Priority.ALWAYS);
        Label next = new Label("Agent 安装将在下一阶段启用。");
        next.setWrapText(true);

        loadRuntimeStatus(context.capabilityBus()).whenComplete((status, error) ->
                Platform.runLater(() -> result.setText(error == null
                        ? new GsonBuilder().setPrettyPrinting().create().toJson(status)
                        : "无法读取程序级能力：" + rootMessage(error))));

        VBox root = new VBox(10, title, result, next);
        root.setPadding(new Insets(12));
        return root;
    }

    CompletableFuture<JsonElement> loadRuntimeStatus(CapabilityBus capabilityBus) {
        return RuntimeStatusClient.query(capabilityBus);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

