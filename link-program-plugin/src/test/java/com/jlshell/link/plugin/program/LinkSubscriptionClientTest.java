package com.jlshell.link.plugin.program;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkSubscriptionClientTest {
    @TempDir Path temporaryDirectory;

    @Test
    void refreshesPlusEntitlementsAndAllowsSessionFeature() {
        try (Fixture fixture = fixture(true, "ALLOWED", true)) {
            JsonObject status = fixture.client.refreshSubscription().join().getAsJsonObject();

            assertThat(status.get("state").getAsString()).isEqualTo("READY");
            assertThat(status.getAsJsonObject("entitlement").get("plan").getAsString()).isEqualTo("PLUS");
            assertThat(fixture.client.authorizeSession("link.tcp-tunnel").join()).isNotNull();
        }
    }

    @Test
    void deniesCapabilitiesWhenSessionPolicyIsDisabled() {
        try (Fixture fixture = fixture(true, "DISABLED_BY_ADMIN", false)) {
            JsonObject status = fixture.client.refreshSubscription().join().getAsJsonObject();

            assertThat(status.get("state").getAsString()).isEqualTo("DISABLED_BY_ADMIN");
            assertThatThrownBy(() -> fixture.client.authorizeSession("link.tcp-tunnel").join())
                    .hasRootCauseMessage("JLShell Link 已被管理员停用");
        }
    }

    @Test
    void reportsTrialWhenFreePlanLacksLinkEntitlement() {
        try (Fixture fixture = fixture(false, "ALLOWED", true)) {
            JsonObject status = fixture.client.refreshSubscription().join().getAsJsonObject();

            assertThat(status.get("state").getAsString()).isEqualTo("TRIAL_AVAILABLE");
            assertThatThrownBy(() -> fixture.client.authorizeProgram("link.tcp-tunnel").join())
                    .hasRootCauseMessage("当前套餐不能使用此功能，可先领取 14 天 Pro 试用");
        }
    }

    private Fixture fixture(boolean plus, String sessionReason, boolean sessionAllowed) {
        MemoryStorage storage = new MemoryStorage();
        MemorySecrets secrets = new MemorySecrets();
        secrets.putText("account.access-token", "test-token");
        secrets.putText("account.token-expiry", Instant.now().plusSeconds(3_600).toString());
        secrets.putText("account.profile", "{\"username\":\"tester\"}");
        ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
        LinkAccountClient.RequestTransport transport = (method, path, body, token) -> {
            assertThat(token).isEqualTo("test-token");
            if (path.equals("/api/v1/account/entitlements")) {
                return json(plus
                        ? "{\"plan\":\"PLUS\",\"entitlements\":[\"link.tcp-tunnel\",\"link.agent-deploy\"],"
                            + "\"limits\":{},\"effectiveUntil\":null,\"trialAvailable\":false,\"trialExpiresAt\":null}"
                        : "{\"plan\":\"FREE\",\"entitlements\":[],\"limits\":{},\"effectiveUntil\":null,"
                            + "\"trialAvailable\":true,\"trialExpiresAt\":null}");
            }
            if (path.startsWith("/api/v1/account/plugin-access")) {
                boolean sessionScope = path.contains("scope=SESSION");
                boolean allowed = !sessionScope || sessionAllowed;
                String reason = sessionScope ? sessionReason : "ALLOWED";
                return json("{\"allowed\":" + allowed + ",\"reason\":\"" + reason
                        + "\",\"currentPlan\":\"" + (plus ? "PLUS" : "FREE")
                        + "\",\"minimumPlan\":\"FREE\",\"enabled\":" + allowed + "}");
            }
            throw new AssertionError("Unexpected request: " + method + " " + path);
        };
        LinkAccountClient client = new LinkAccountClient(
                storage, secrets, connector, () -> "ab".repeat(32), transport);
        return new Fixture(client, connector);
    }

    private static JsonElement json(String value) {
        return JsonParser.parseString(value);
    }

    private record Fixture(LinkAccountClient client, ConnectorProcessManager connector) implements AutoCloseable {
        @Override public void close() {
            client.close();
            connector.close();
        }
    }

    private static final class MemoryStorage implements PluginStorage {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Set<String> keys() { return Set.copyOf(values.keySet()); }
        @Override public void clear() { values.clear(); }
    }

    private static final class MemorySecrets implements SecureStorage {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public boolean available() { return true; }
        @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void put(String key, byte[] value) { values.put(key, value.clone()); }
        void putText(String key, String value) { put(key, value.getBytes(StandardCharsets.UTF_8)); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Set<String> keys() { return Set.copyOf(values.keySet()); }
        @Override public void clear() { values.clear(); }
    }
}
