package com.jlshell.link.plugin.program;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jlshell.program.api.AccountRequest;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;

/**
 * Link 控制平面客户端。
 *
 * <p>登录、令牌续期、设备 ID 与 HTTPS 请求认证全部由 JLShell 宿主处理；本类只编排
 * Link 业务请求，永不保存或读取 JWT。</p>
 */
final class LinkAccountClient implements AutoCloseable {

    private final AccountSessionService accountSession;
    private final ConnectorProcessManager connector;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jlshell-link-control-plane");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String deviceRecordId;

    LinkAccountClient(AccountSessionService accountSession, ConnectorProcessManager connector) {
        this.accountSession = accountSession == null ? AccountSessionService.unavailable() : accountSession;
        this.connector = connector;
    }

    JsonObject status() {
        AccountSession session = accountSession.snapshot();
        JsonObject result = new JsonObject();
        result.addProperty("available", !session.baseUrl().isBlank());
        result.addProperty("state", session.authenticated() ? "AUTHENTICATED" : "SIGNED_OUT");
        result.addProperty("baseUrl", session.baseUrl());
        result.addProperty("deviceId", session.deviceId());
        if (session.authenticated()) {
            JsonObject account = new JsonObject();
            account.addProperty("id", session.accountId());
            account.addProperty("username", session.username());
            account.addProperty("email", session.email());
            account.addProperty("role", session.role());
            result.add("account", account);
            result.addProperty("expiresAt", session.expiresAt());
        } else {
            result.add("account", JsonNull.INSTANCE);
        }
        return result;
    }

    CompletableFuture<JsonElement> catalog() {
        return authenticated(() -> {
            JsonArray agents = request("GET", "/api/v1/link/agents", null).getAsJsonArray();
            for (JsonElement entry : agents) {
                JsonObject agent = entry.getAsJsonObject();
                String id = agent.get("id").getAsString();
                agent.add("targets", request("GET", "/api/v1/link/agents/" + encode(id) + "/targets", null)
                        .getAsJsonArray());
            }
            JsonObject result = new JsonObject();
            result.add("agents", agents);
            result.add("relays", request("GET", "/api/v1/link/relays", null));
            result.addProperty("deviceId", requireDeviceRecord());
            return result;
        });
    }

    CompletableFuture<JsonElement> issueTicket(JsonElement args) {
        return authenticated(() -> {
            requireAccess(SESSION_SCOPE, "link.tcp-tunnel");
            JsonObject input = object(args);
            JsonObject body = new JsonObject();
            body.addProperty("deviceId", requireDeviceRecord());
            body.addProperty("agentId", required(input, "agentId"));
            body.addProperty("targetIp", required(input, "targetIp"));
            body.addProperty("targetPort", requiredInt(input, "targetPort"));
            return request("POST", "/api/v1/link/tickets", body);
        });
    }

    CompletableFuture<JsonElement> agentChallenge(JsonElement args) {
        return authenticated(() -> {
            requireAccess(SESSION_SCOPE, "link.agent-deploy");
            JsonObject body = new JsonObject();
            body.addProperty("purpose", "AGENT");
            body.addProperty("publicKey", required(object(args), "publicKey"));
            return request("POST", "/api/v1/link/node-challenges", body);
        });
    }

    CompletableFuture<JsonElement> registerAgent(JsonElement args) {
        return authenticated(() -> {
            requireAccess(SESSION_SCOPE, "link.agent-deploy");
            JsonObject input = object(args);
            String publicKey = required(input, "publicKey");
            JsonObject registration = null;
            JsonArray existingAgents = request("GET", "/api/v1/link/agents", null).getAsJsonArray();
            for (JsonElement item : existingAgents) {
                JsonObject existing = item.getAsJsonObject();
                if (publicKey.equals(existing.get("publicKey").getAsString())
                        && !"REVOKED".equals(existing.get("state").getAsString())) {
                    JsonObject rotated = request("POST", "/api/v1/link/agents/"
                            + encode(existing.get("id").getAsString()) + "/credentials/rotate", null)
                            .getAsJsonObject();
                    registration = new JsonObject();
                    registration.add("agent", existing.deepCopy());
                    registration.addProperty("credential", rotated.get("credential").getAsString());
                    break;
                }
            }
            JsonObject body = new JsonObject();
            for (String name : java.util.List.of("name", "platform", "architecture", "publicKey",
                    "version", "challengeId", "proofSignature")) {
                body.addProperty(name, required(input, name));
            }
            if (registration == null) registration = request("POST", "/api/v1/link/agents", body).getAsJsonObject();
            if (input.has("targetIp") && input.has("targetPort")) {
                JsonObject target = new JsonObject();
                target.addProperty("targetIp", required(input, "targetIp"));
                target.addProperty("targetPort", requiredInt(input, "targetPort"));
                target.addProperty("description", "JLShell SSH");
                String id = registration.getAsJsonObject("agent").get("id").getAsString();
                JsonArray targets = request("GET", "/api/v1/link/agents/" + encode(id) + "/targets", null)
                        .getAsJsonArray();
                JsonElement matching = null;
                for (JsonElement item : targets) {
                    JsonObject existing = item.getAsJsonObject();
                    if (target.get("targetIp").getAsString().equals(existing.get("targetIp").getAsString())
                            && target.get("targetPort").getAsInt() == existing.get("targetPort").getAsInt()
                            && existing.get("enabled").getAsBoolean()) {
                        matching = item;
                        break;
                    }
                }
                registration.add("target", matching == null
                        ? request("POST", "/api/v1/link/agents/" + encode(id) + "/targets", target)
                        : matching.deepCopy());
            }
            return registration;
        });
    }

    CompletableFuture<JsonElement> authority() {
        return authenticated(() -> request("GET", "/api/v1/link/ticket-authority", null));
    }

    private JsonElement request(String method, String path, JsonElement body) throws Exception {
        return accountSession.request(new AccountRequest(method, path, body)).get();
    }

    private String requireDeviceRecord() throws Exception {
        if (deviceRecordId != null) return deviceRecordId;
        AccountSession session = accountSession.snapshot();
        if (!session.authenticated() || session.deviceId().isBlank()) {
            throw new IllegalStateException("请先在 JLShell 中登录账号");
        }
        JsonArray devices = request("GET", "/api/v1/account/devices", null).getAsJsonArray();
        JsonObject owned = null;
        for (JsonElement item : devices) {
            if (session.deviceId().equals(item.getAsJsonObject().get("deviceId").getAsString())) {
                owned = item.getAsJsonObject();
                break;
            }
        }
        if (owned == null) throw new IllegalStateException("JLShell 宿主设备记录尚未创建");
        deviceRecordId = owned.get("id").getAsString();
        if (owned.has("peerId") && !owned.get("peerId").isJsonNull()
                && connector.peerId().equals(owned.get("peerId").getAsString())) return deviceRecordId;
        JsonObject challengeBody = new JsonObject();
        challengeBody.addProperty("purpose", "DEVICE");
        challengeBody.addProperty("publicKey", connector.publicKey());
        JsonObject challenge = request("POST", "/api/v1/link/node-challenges", challengeBody).getAsJsonObject();
        JsonObject proof = new JsonObject();
        proof.addProperty("publicKey", connector.publicKey());
        proof.addProperty("challengeId", challenge.get("challengeId").getAsString());
        proof.addProperty("proofSignature", connector.signChallenge(challenge.get("payload").getAsString()));
        request("PUT", "/api/v1/link/devices/" + encode(deviceRecordId) + "/identity", proof);
        return deviceRecordId;
    }

    private CompletableFuture<JsonElement> authenticated(CheckedSupplier action) {
        return CompletableFuture.supplyAsync(() -> {
            if (!accountSession.snapshot().authenticated()) {
                throw new IllegalStateException("请先在 JLShell 的账号设置中通过 Web 登录");
            }
            try {
                return action.get();
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("JSON object required");
        return value.getAsJsonObject();
    }

    private static String required(JsonObject value, String name) {
        if (!value.has(name) || value.get(name).isJsonNull()) throw new IllegalArgumentException(name + " is required");
        String result = value.get(name).getAsString().trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    private static int requiredInt(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive()) throw new IllegalArgumentException(name + " is required");
        int result = value.get(name).getAsInt();
        if (result < 1 || result > 65535) throw new IllegalArgumentException(name + " is invalid");
        return result;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface CheckedSupplier { JsonElement get() throws Exception; }
}
