package com.jlshell.link.plugin.program;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.event.ProjectDeletedEvent;
import com.jlshell.plugin.api.event.SessionOpenedEvent;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.storage.PluginStorage;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public final class JlShellLinkProgramPlugin implements JlShellProgramPlugin {

    private static final long MAX_AGENT_BYTES = 200L * 1024 * 1024;
    private ProgramPluginContext context;
    private ConnectorProcessManager connectorManager;
    private final List<Registration> registrations = new ArrayList<>();
    private final Map<String, String> sessionProjects = new ConcurrentHashMap<>();

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
        connectorManager = new ConnectorProcessManager(ConnectorConfiguration.load(context.storage()));
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.RUNTIME_STATUS_CAPABILITY)
                .description("Return the process-wide JLShell Link runtime status.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(connectorManager.status()))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TUNNEL_OPEN_CAPABILITY)
                .description("Start a loopback-only Connector tunnel from a signed Link ticket.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> connectorManager.open(args))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.TUNNEL_CLOSE_CAPABILITY)
                .description("Stop a Connector tunnel owned by this plugin process.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> connectorManager.close(args))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY)
                .description("Return whether the current session project requested Agent guidance.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> CompletableFuture.completedFuture(
                        projectAgentIntent(requiredString(args.getAsJsonObject(), "sessionId"))))
                .build());
        context.capabilityRegistry().register(Capability.builder(LinkPluginContract.AGENT_INSTALL_SPEC_CAPABILITY)
                .description("Resolve a locally configured Agent binary for a supported remote platform.")
                .requiresSession(false)
                .handler((args, capabilityContext) -> agentInstallSpec(args))
                .build());
        if (context.projectIntegration().available()) {
            registrations.add(context.projectIntegration().register(new LinkProjectContribution(context.storage())));
        }
        if (context.hostEvents().available()) {
            registrations.add(context.hostEvents().subscribe(SessionOpenedEvent.class, event -> {
                if (event.projectId() == null) {
                    sessionProjects.remove(event.sessionId());
                } else {
                    sessionProjects.put(event.sessionId(), event.projectId());
                }
            }));
            registrations.add(context.hostEvents().subscribe(ProjectDeletedEvent.class, event -> {
                PluginStorage storage = context.storage();
                if (storage != null) {
                    storage.remove(LinkProjectContribution.projectKey(event.projectId()));
                }
                sessionProjects.entrySet().removeIf(entry -> event.projectId().equals(entry.getValue()));
            }));
        }
        context.info("JLShell Link program plugin activated");
    }

    @Override
    public void deactivate() {
        if (context == null) {
            return;
        }
        for (Registration registration : registrations) {
            try {
                registration.close();
            } catch (RuntimeException error) {
                context.warn("Cannot release JLShell Link registration: " + error.getMessage());
            }
        }
        registrations.clear();
        sessionProjects.clear();
        for (String capability : List.of(
                LinkPluginContract.RUNTIME_STATUS_CAPABILITY,
                LinkPluginContract.TUNNEL_OPEN_CAPABILITY,
                LinkPluginContract.TUNNEL_CLOSE_CAPABILITY,
                LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY,
                LinkPluginContract.AGENT_INSTALL_SPEC_CAPABILITY)) {
            context.capabilityRegistry().unregister(capability);
        }
        connectorManager.close();
        connectorManager = null;
        context.info("JLShell Link program plugin deactivated");
        context = null;
    }

    @Override
    public Node settingsView(ProgramPluginContext context) {
        ConnectorConfiguration configuration = ConnectorConfiguration.load(context.storage()).normalized();
        Label title = new Label("JLShell Link Runtime");
        TextField connector = new TextField(text(configuration.connectorBinary()));
        connector.setPromptText("jlshell-connector 可执行文件路径");
        TextField identity = new TextField(text(configuration.identityFile()));
        identity.setPromptText("Connector 身份文件路径");
        TextField agents = new TextField(text(configuration.agentBundleDirectory()));
        agents.setPromptText("Agent 三平台二进制目录");
        Label status = new Label(statusText());
        status.setWrapText(true);
        Button save = new Button("保存并检测 Connector");
        save.setOnAction(event -> {
            try {
                ConnectorConfiguration updated = new ConnectorConfiguration(
                        path(connector.getText()), path(identity.getText()), path(agents.getText())).normalized();
                updated.save(context.storage());
                connectorManager.configure(updated);
                status.setText("配置已保存，正在检测 Connector 身份…");
                context.showNotification("JLShell Link 配置已保存", NotificationLevel.INFO);
            } catch (RuntimeException error) {
                status.setText("配置无效：" + error.getMessage());
                context.showNotification("JLShell Link 配置无效", NotificationLevel.ERROR);
            }
        });
        Button refresh = new Button("刷新状态");
        refresh.setOnAction(event -> status.setText(statusText()));
        Label note = new Label("票据只写入 0600 临时文件，Connector 仅绑定回环地址；"
                + "Agent 目录中的文件名必须使用发布约定名称。");
        note.setWrapText(true);
        VBox root = new VBox(8, title, new Label("Connector"), connector,
                new Label("身份文件"), identity, new Label("Agent 发布目录"), agents,
                save, refresh, status, note);
        root.setPadding(new Insets(12));
        return root;
    }

    private JsonObject projectAgentIntent(String sessionId) {
        JsonObject result = new JsonObject();
        String projectId = sessionProjects.get(sessionId);
        boolean requested = false;
        if (projectId != null && context.storage() != null) {
            requested = Boolean.parseBoolean(context.storage().get(
                    LinkProjectContribution.projectKey(projectId), "false"));
        }
        result.addProperty("requested", requested);
        if (projectId == null) {
            result.add("projectId", com.google.gson.JsonNull.INSTANCE);
        } else {
            result.addProperty("projectId", projectId);
        }
        return result;
    }

    private CompletableFuture<com.google.gson.JsonElement> agentInstallSpec(com.google.gson.JsonElement args) {
        try {
            if (args == null || !args.isJsonObject()) {
                throw new IllegalArgumentException("platform and architecture are required");
            }
            JsonObject object = args.getAsJsonObject();
            String platform = requiredString(object, "platform");
            String architecture = requiredString(object, "architecture");
            Path binary = connectorManager.agentBinary(platform, architecture);
            long size = Files.size(binary);
            if (size < 1 || size > MAX_AGENT_BYTES) {
                throw new IllegalStateException("Agent binary is empty or exceeds 200 MiB");
            }
            JsonObject result = new JsonObject();
            result.addProperty("platform", platform);
            result.addProperty("architecture", architecture);
            result.addProperty("path", binary.toString());
            result.addProperty("size", size);
            result.addProperty("sha256", sha256(binary));
            return CompletableFuture.completedFuture(result);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private String statusText() {
        JsonObject status = connectorManager.status();
        return "Connector：" + status.get("state").getAsString()
                + "，活跃隧道：" + status.get("activeTunnels").getAsInt();
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String value = object.get(name).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String text(Path path) {
        return path == null ? "" : path.toString();
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value.trim());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
