package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.jlshell.plugin.api.storage.PluginStorage;
import org.junit.jupiter.api.Test;

class LinkBindingStoreTest {
    @Test
    void savesLoadsAndRemovesProjectBindings() {
        MapStorage storage = new MapStorage();
        LinkBindingStore bindings = new LinkBindingStore(storage);
        var session = new LinkBindingStore.SessionReference("project-1", "connection-1");

        var saved = bindings.save(session, "agent-1", "127.0.0.1", 22);

        assertThat(saved.getAsJsonObject("binding").get("agentId").getAsString()).isEqualTo("agent-1");
        assertThat(bindings.get(session).getAsJsonObject("binding").get("targetPort").getAsInt()).isEqualTo(22);
        bindings.removeProject("project-1");
        assertThat(bindings.get(session).get("binding").isJsonNull()).isTrue();
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
