package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkAccountClientTest {
    @TempDir Path temporaryDirectory;

    @Test
    void storesStableMachineIdOnlyInSecureStorageAndAllowsLoopbackDevelopment() {
        MemoryStorage normal = new MemoryStorage();
        MemorySecrets secure = new MemorySecrets();
        try (ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
             LinkAccountClient client = new LinkAccountClient(normal, secure, connector)) {
            client.configureBaseUrl("http://127.0.0.1:8080");
            String first = client.status().get("deviceId").getAsString();
            String second = client.status().get("deviceId").getAsString();
            assertThat(first).isEqualTo(second).startsWith("desktop-");
            assertThat(normal.values).doesNotContainKey("account.device-id");
            assertThat(secure.values).containsKey("account.device-id");
        }
    }

    @Test
    void rejectsPlaintextRemoteWebsite() {
        try (ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
             LinkAccountClient client = new LinkAccountClient(new MemoryStorage(), new MemorySecrets(), connector)) {
            assertThatThrownBy(() -> client.configureBaseUrl("http://example.com"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void usesProductionWebsiteWithoutUserConfiguration() {
        try (ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
             LinkAccountClient client = new LinkAccountClient(new MemoryStorage(), new MemorySecrets(), connector)) {
            assertThat(client.configuredBaseUrl()).isEqualTo(LinkAccountClient.DEFAULT_BASE_URL);
            assertThat(client.status().get("baseUrl").getAsString()).isEqualTo(LinkAccountClient.DEFAULT_BASE_URL);
        }
    }

    private static class MemoryStorage implements PluginStorage {
        final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Set<String> keys() { return Set.copyOf(values.keySet()); }
        @Override public void clear() { values.clear(); }
    }

    private static final class MemorySecrets implements SecureStorage {
        final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public boolean available() { return true; }
        @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void put(String key, byte[] value) { values.put(key, value.clone()); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Set<String> keys() { return Set.copyOf(values.keySet()); }
        @Override public void clear() { values.clear(); }
    }
}
