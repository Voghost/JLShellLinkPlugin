package com.jlshell.link.plugin.program;

import java.util.concurrent.CompletableFuture;

import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.rpc.Capability;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public final class JlShellLinkProgramPlugin implements JlShellProgramPlugin {

    private ProgramPluginContext context;

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
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.RUNTIME_STATUS_CAPABILITY)
                .description("Return the process-wide JLShell Link runtime status.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        LinkPluginContract.notConfiguredStatus()))
                .build());
        context.info("JLShell Link program plugin activated");
    }

    @Override
    public void deactivate() {
        if (context == null) {
            return;
        }
        context.capabilityRegistry().unregister(LinkPluginContract.RUNTIME_STATUS_CAPABILITY);
        context.info("JLShell Link program plugin deactivated");
        context = null;
    }

    @Override
    public Node settingsView(ProgramPluginContext context) {
        Label title = new Label("JLShell Link（阶段 0）");
        Label account = new Label("账号：尚未配置");
        Label connector = new Label("Connector：尚未配置");
        Label agent = new Label("Agent：尚未配置");
        Label note = new Label("订阅、Connector 启动和 Agent 自动部署将在后续阶段启用。");
        note.setWrapText(true);
        VBox root = new VBox(8, title, account, connector, agent, note);
        root.setPadding(new Insets(12));
        return root;
    }
}

