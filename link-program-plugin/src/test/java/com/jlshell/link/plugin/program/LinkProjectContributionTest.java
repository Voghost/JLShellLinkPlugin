package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.event.ProjectUpdatedEvent;
import com.jlshell.plugin.api.project.ProjectCreationContext;
import com.jlshell.plugin.api.project.ProjectManagementContext;
import com.jlshell.plugin.api.storage.PluginStorage;
import javafx.beans.property.SimpleStringProperty;
import org.junit.jupiter.api.Test;

class LinkProjectContributionTest {

    @Test
    void persistsAgentIntentAfterProjectCreation() {
        MapStorage storage = new MapStorage();
        LinkProjectContribution contribution = new LinkProjectContribution(storage, null, null, null);
        ProjectCreationContext context = new ProjectCreationContext(
                new SimpleStringProperty("project"), new SimpleStringProperty("description"));
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, "true");

        contribution.onProjectCreated(new ProjectCreatedEvent(
                "project-id", "project", "description", Instant.EPOCH), context);

        assertThat(storage.get(LinkProjectContribution.projectKey("project-id"))).isEqualTo("true");
    }

    @Test
    void persistsDisabledIntentWhenExistingProjectIsSaved() {
        MapStorage storage = new MapStorage();
        storage.put(LinkProjectContribution.projectKey("project-id"), "true");
        LinkProjectContribution contribution = new LinkProjectContribution(storage, null, null, null);
        ProjectManagementContext context = new ProjectManagementContext("project-id",
                new SimpleStringProperty("project"), new SimpleStringProperty("description"));
        context.putState(LinkPluginContract.PROJECT_AGENT_REQUESTED_STATE, "false");

        contribution.onProjectUpdated(new ProjectUpdatedEvent(
                "project-id", "project", "description", Instant.EPOCH), context);

        assertThat(storage.get(LinkProjectContribution.projectKey("project-id"))).isEqualTo("false");
    }

    private static final class MapStorage implements PluginStorage {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Set<String> keys() { return Set.copyOf(values.keySet()); }
        @Override public void clear() { values.clear(); }
    }
}
