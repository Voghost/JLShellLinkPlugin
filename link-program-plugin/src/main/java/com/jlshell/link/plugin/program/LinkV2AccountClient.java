package com.jlshell.link.plugin.program;

import com.jlshell.link.client.WebsiteClientIdentityBinder;
import com.jlshell.link.core.identity.LocalNodeKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.program.api.AccountRequest;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Account-bound v2 requests. JWT handling and renewal remain exclusively in the desktop host. */
final class LinkV2AccountClient {
    private final AccountSessionService accounts;

    LinkV2AccountClient(AccountSessionService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    CompletableFuture<JsonArray> agents() {
        return request("GET", "/api/v2/link/agents", null).thenApply(response -> {
            if (!response.isJsonArray()) throw new IllegalStateException("Invalid Link Agent directory response");
            return response.getAsJsonArray();
        });
    }

    CompletableFuture<JsonObject> accessPolicy(UUID agentId) {
        return request("GET", "/api/v2/link/agents/" + Objects.requireNonNull(agentId)
                + "/access-policy", null).thenApply(LinkV2AccountClient::object);
    }

    /** The enrollment secret is returned only to its caller and must never enter PluginStorage. */
    CompletableFuture<JsonObject> createEnrollment(String name) {
        if (name == null || name.isBlank() || name.length() > 120) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Agent name is invalid"));
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", name.strip());
        return request("POST", "/api/v2/link/enrollments", body).thenApply(LinkV2AccountClient::object);
    }

    CompletableFuture<UUID> hostDeviceRecordId() {
        AccountSession snapshot = accounts.snapshot();
        return hostDeviceRecordId(snapshot.deviceId());
    }

    CompletableFuture<UUID> hostDeviceRecordId(String expectedHostDeviceId) {
        AccountSession snapshot = accounts.snapshot();
        if (!snapshot.authenticated() || snapshot.deviceId() == null || snapshot.deviceId().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("JLShell account is not signed in"));
        }
        if (!Objects.equals(expectedHostDeviceId, snapshot.deviceId())) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "JLShell account device changed before Link identity binding"));
        }
        return request("GET", "/api/v1/account/devices", null).thenApply(response -> {
            if (!response.isJsonArray()) throw new IllegalStateException("Invalid device directory response");
            for (JsonElement entry : response.getAsJsonArray()) {
                JsonObject device = object(entry);
                if (snapshot.deviceId().equals(string(device, "deviceId"))) {
                    return UUID.fromString(string(device, "id"));
                }
            }
            throw new IllegalStateException("JLShell host device is not registered");
        });
    }

    CompletableFuture<ControlCredential> issueControlCredential(UUID deviceId, String fingerprint) {
        Objects.requireNonNull(deviceId, "deviceId");
        if (fingerprint == null || !fingerprint.matches("[0-9a-fA-F]{64}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid client key fingerprint"));
        }
        JsonObject body = new JsonObject();
        body.addProperty("deviceId", deviceId.toString());
        body.addProperty("clientKeyFingerprint", fingerprint);
        return request("POST", "/api/v2/link/control-credentials", body).thenApply(response -> {
            JsonObject value = object(response);
            return new ControlCredential(string(value, "credential"),
                    Instant.parse(string(value, "expiresAt")));
        });
    }

    CompletionStage<WebsiteClientIdentityBinder.DeviceIdentity> bindClientIdentity(
            UUID deviceRecordId, LocalNodeKey nodeKey) {
        WebsiteClientIdentityBinder binder = new WebsiteClientIdentityBinder((method, path, jsonBody) -> {
            JsonElement body = jsonBody == null ? null : com.google.gson.JsonParser.parseString(jsonBody);
            return accounts.request(new AccountRequest(method, path, body)).thenApply(JsonElement::toString);
        });
        return binder.bind(deviceRecordId, nodeKey);
    }

    private CompletableFuture<JsonElement> request(String method, String path, JsonElement body) {
        if (!accounts.snapshot().authenticated()) {
            return CompletableFuture.failedFuture(new IllegalStateException("请先在 JLShell 中登录账号"));
        }
        return accounts.request(new AccountRequest(method, path, body));
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalStateException("Invalid Link response");
        return value.getAsJsonObject();
    }

    private static String string(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).isJsonNull()) {
            throw new IllegalStateException("Missing Link response field: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) throw new IllegalStateException("Empty Link response field: " + key);
        return result;
    }

    record ControlCredential(String value, Instant expiresAt) {
        ControlCredential {
            if (value == null || value.isBlank() || value.length() > 4096) {
                throw new IllegalArgumentException("Invalid Link control credential");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
        @Override public String toString() { return "ControlCredential[<redacted>]"; }
    }
}
