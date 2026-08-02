package com.jlshell.link.plugin.program;

import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.project.ProjectCreationContext;
import com.jlshell.plugin.api.project.ProjectCreationContribution;
import com.jlshell.plugin.api.storage.PluginStorage;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

final class LinkProjectContribution implements ProjectCreationContribution {

    private final PluginStorage storage;

    LinkProjectContribution(PluginStorage storage) {
        this.storage = storage;
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
        CheckBox requested = new CheckBox("为此项目启用 JLShell Link Agent 引导");
        requested.selectedProperty().addListener((observable, oldValue, newValue) ->
                context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE,
                        Boolean.toString(newValue)));
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, "false");
        Label description = new Label("首次打开项目内 SSH 会话时，可检测服务器平台并安全上传 Agent。");
        description.setWrapText(true);
        return new VBox(6, requested, description);
    }

    @Override
    public void onProjectCreated(ProjectCreatedEvent event, ProjectCreationContext context) {
        if (storage != null && Boolean.parseBoolean(
                context.state(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE))) {
            storage.put(projectKey(event.projectId()), "true");
        }
    }

    static String projectKey(String projectId) {
        return "project." + projectId + ".agent-requested";
    }
}
