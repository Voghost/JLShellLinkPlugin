package com.jlshell.link.plugin.program;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class LinkAccountClient implements AutoCloseable {
    static final String DEFAULT_BASE_URL = "https://jlshell.oomn.net";
    private static final String BASE_URL_KEY = "account.base-url";
    private static final String TOKEN_KEY = "account.access-token";
    private static final String TOKEN_EXPIRY_KEY = "account.token-expiry";
    private static final String ACCOUNT_KEY = "account.profile";
    private static final String DEVICE_ID_KEY = "account.device-id";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PluginStorage storage;
    private final SecureStorage secrets;
    private final ConnectorProcessManager connector;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jlshell-link-account");
        thread.setDaemon(true);
        return thread;
    });
    private volatile URI baseUri;
    private volatile String token;
    private volatile Instant tokenExpiry;
    private volatile JsonObject account;
    private volatile String deviceRecordId;
    private volatile String state = "SIGNED_OUT";
    private volatile String message;
    private volatile HttpServer loginServer;

    LinkAccountClient(PluginStorage storage, SecureStorage secrets, ConnectorProcessManager connector) {
        this.storage = storage;
        this.secrets = secrets;
        this.connector = connector;
        String configured = storage == null ? null : storage.get(BASE_URL_KEY);
        if (configured != null && !configured.isBlank()) {
            baseUri = validateBaseUri(configured);
        } else {
            baseUri = validateBaseUri(DEFAULT_BASE_URL);
        }
        if (secrets.available()) {
            token = secret(TOKEN_KEY);
            String expiry = secret(TOKEN_EXPIRY_KEY);
            String profile = secret(ACCOUNT_KEY);
            if (expiry != null) {
                try { tokenExpiry = Instant.parse(expiry); } catch (RuntimeException ignored) { clearSession(); }
            }
            if (profile != null) {
                try { account = JsonParser.parseString(profile).getAsJsonObject(); }
                catch (RuntimeException ignored) { clearSession(); }
            }
            if (token != null && tokenExpiry != null && tokenExpiry.isAfter(Instant.now())) {
                state = "AUTHENTICATED";
            } else if (token != null) {
                clearSession();
            }
        }
        executor.scheduleWithFixedDelay(this::renewQuietly, 5, 5, TimeUnit.MINUTES);
    }

    String configuredBaseUrl() {
        return baseUri == null ? "" : baseUri.toString();
    }

    void configureBaseUrl(String value) {
        URI parsed = validateBaseUri(value);
        if (baseUri != null && !baseUri.equals(parsed)) {
            clearSession();
        }
        baseUri = parsed;
        if (storage != null) storage.put(BASE_URL_KEY, parsed.toString());
    }

    JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("available", secrets.available() && baseUri != null);
        result.addProperty("state", state);
        result.addProperty("baseUrl", configuredBaseUrl());
        result.addProperty("deviceId", deviceId());
        if (account == null) result.add("account", com.google.gson.JsonNull.INSTANCE);
        else result.add("account", account.deepCopy());
        if (tokenExpiry != null) result.addProperty("expiresAt", tokenExpiry.toString());
        if (message != null) result.addProperty("message", message);
        return result;
    }

    CompletableFuture<JsonElement> startLogin() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (JsonElement) startLoginBlocking();
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    CompletableFuture<JsonElement> logout() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (token != null && baseUri != null) request("POST", "/api/v1/account/logout", null, true);
            } catch (Exception ignored) {
                // Local logout must still invalidate the encrypted cached token.
            }
            clearSession();
            return (JsonElement) status();
        }, executor);
    }

    CompletableFuture<JsonElement> catalog() {
        return authenticated(() -> {
            JsonArray agents = request("GET", "/api/v1/link/agents", null, true).getAsJsonArray();
            for (JsonElement entry : agents) {
                JsonObject agent = entry.getAsJsonObject();
                String id = agent.get("id").getAsString();
                agent.add("targets", request("GET", "/api/v1/link/agents/" + encode(id) + "/targets",
                        null, true).getAsJsonArray());
            }
            JsonObject result = new JsonObject();
            result.add("agents", agents);
            result.add("relays", request("GET", "/api/v1/link/relays", null, true));
            result.addProperty("deviceId", requireDeviceRecord());
            return result;
        });
    }

    CompletableFuture<JsonElement> issueTicket(JsonElement args) {
        return authenticated(() -> {
            JsonObject input = object(args);
            JsonObject body = new JsonObject();
            body.addProperty("deviceId", requireDeviceRecord());
            body.addProperty("agentId", required(input, "agentId"));
            body.addProperty("targetIp", required(input, "targetIp"));
            body.addProperty("targetPort", requiredInt(input, "targetPort"));
            return request("POST", "/api/v1/link/tickets", body, true);
        });
    }

    CompletableFuture<JsonElement> agentChallenge(JsonElement args) {
        return authenticated(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("purpose", "AGENT");
            body.addProperty("publicKey", required(object(args), "publicKey"));
            return request("POST", "/api/v1/link/node-challenges", body, true);
        });
    }

    CompletableFuture<JsonElement> registerAgent(JsonElement args) {
        return authenticated(() -> {
            JsonObject input = object(args);
            String publicKey = required(input, "publicKey");
            JsonObject registration = null;
            JsonArray existingAgents = request("GET", "/api/v1/link/agents", null, true).getAsJsonArray();
            for (JsonElement item : existingAgents) {
                JsonObject existing = item.getAsJsonObject();
                if (publicKey.equals(existing.get("publicKey").getAsString())
                        && !"REVOKED".equals(existing.get("state").getAsString())) {
                    JsonObject rotated = request("POST", "/api/v1/link/agents/"
                            + encode(existing.get("id").getAsString()) + "/credentials/rotate",
                            null, true).getAsJsonObject();
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
            if (registration == null) {
                registration = request("POST", "/api/v1/link/agents", body, true).getAsJsonObject();
            }
            if (input.has("targetIp") && input.has("targetPort")) {
                JsonObject target = new JsonObject();
                target.addProperty("targetIp", required(input, "targetIp"));
                target.addProperty("targetPort", requiredInt(input, "targetPort"));
                target.addProperty("description", "JLShell SSH");
                String id = registration.getAsJsonObject("agent").get("id").getAsString();
                JsonArray targets = request("GET", "/api/v1/link/agents/" + encode(id) + "/targets",
                        null, true).getAsJsonArray();
                JsonElement matching = null;
                for (JsonElement item : targets) {
                    JsonObject existing = item.getAsJsonObject();
                    if (required(input, "targetIp").equals(existing.get("targetIp").getAsString())
                            && requiredInt(input, "targetPort") == existing.get("targetPort").getAsInt()
                            && existing.get("enabled").getAsBoolean()) {
                        matching = item;
                        break;
                    }
                }
                registration.add("target", matching == null
                        ? request("POST", "/api/v1/link/agents/" + encode(id) + "/targets", target, true)
                        : matching.deepCopy());
            }
            return registration;
        });
    }

    CompletableFuture<JsonElement> authority() {
        return CompletableFuture.supplyAsync(() -> {
            try { return request("GET", "/api/v1/link/ticket-authority", null, false); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, executor);
    }

    private JsonObject startLoginBlocking() throws Exception {
        if (!secrets.available()) throw new IllegalStateException("JLShell secure storage is unavailable");
        URI base = requireBaseUri();
        if (loginServer != null) throw new IllegalStateException("A desktop login is already in progress");
        String verifier = random(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String expectedState = random(32);
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0), 1);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        CompletableFuture<String> code = new CompletableFuture<>();
        server.createContext("/callback", exchange -> handleCallback(exchange, expectedState, code));
        server.setExecutor(executor);
        server.start();
        loginServer = server;
        state = "AUTHENTICATING";
        String url = base.resolve("/desktop/authorize?code_challenge=" + encode(challenge)
                + "&redirect_uri=" + encode(redirect) + "&state=" + encode(expectedState)
                + "&device_id=" + encode(deviceId()) + "&device_name=" + encode(deviceName())).toString();
        boolean opened = openBrowser(url);
        code.orTimeout(3, TimeUnit.MINUTES).whenCompleteAsync((authorizationCode, error) -> {
            server.stop(0);
            loginServer = null;
            if (error != null) {
                state = "ERROR";
                message = "Desktop login timed out or was rejected";
                return;
            }
            try {
                JsonObject body = new JsonObject();
                body.addProperty("code", authorizationCode);
                body.addProperty("codeVerifier", verifier);
                body.addProperty("redirectUri", redirect);
                saveSession(request("POST", "/api/v1/desktop-token", body, false).getAsJsonObject());
                registerDeviceIdentity();
                state = "AUTHENTICATED";
                message = null;
            } catch (Exception exchangeError) {
                clearSession();
                state = "ERROR";
                message = rootMessage(exchangeError);
            }
        }, executor);
        JsonObject result = new JsonObject();
        result.addProperty("authorizationUrl", url);
        result.addProperty("browserOpened", opened);
        result.addProperty("expiresInSeconds", 180);
        return result;
    }

    private void registerDeviceIdentity() throws Exception {
        JsonArray devices = request("GET", "/api/v1/account/devices", null, true).getAsJsonArray();
        JsonObject owned = null;
        for (JsonElement item : devices) {
            if (deviceId().equals(item.getAsJsonObject().get("deviceId").getAsString())) {
                owned = item.getAsJsonObject();
                break;
            }
        }
        if (owned == null) throw new IllegalStateException("Desktop device record was not created");
        deviceRecordId = owned.get("id").getAsString();
        if (owned.has("peerId") && !owned.get("peerId").isJsonNull()
                && connector.peerId().equals(owned.get("peerId").getAsString())) return;
        JsonObject challengeBody = new JsonObject();
        challengeBody.addProperty("purpose", "DEVICE");
        challengeBody.addProperty("publicKey", connector.publicKey());
        JsonObject challenge = request("POST", "/api/v1/link/node-challenges", challengeBody, true)
                .getAsJsonObject();
        JsonObject proof = new JsonObject();
        proof.addProperty("publicKey", connector.publicKey());
        proof.addProperty("challengeId", challenge.get("challengeId").getAsString());
        proof.addProperty("proofSignature", connector.signChallenge(challenge.get("payload").getAsString()));
        request("PUT", "/api/v1/link/devices/" + encode(deviceRecordId) + "/identity", proof, true);
    }

    private void renewQuietly() {
        if (token == null || baseUri == null) return;
        try {
            saveSession(request("POST", "/api/v1/account/heartbeat", null, true).getAsJsonObject());
            state = "AUTHENTICATED";
            message = null;
        } catch (Exception error) {
            message = rootMessage(error);
            if (tokenExpiry != null && !tokenExpiry.isAfter(Instant.now())) clearSession();
        }
    }

    private void saveSession(JsonObject response) {
        token = required(response, "token");
        tokenExpiry = Instant.parse(required(response, "expiresAt"));
        account = response.getAsJsonObject("account").deepCopy();
        putSecret(TOKEN_KEY, token);
        putSecret(TOKEN_EXPIRY_KEY, tokenExpiry.toString());
        putSecret(ACCOUNT_KEY, account.toString());
    }

    private <T extends JsonElement> CompletableFuture<JsonElement> authenticated(CheckedSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireToken();
                return action.get();
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    private JsonElement request(String method, String path, JsonObject body, boolean authenticated) throws Exception {
        URI uri = requireBaseUri().resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "JLShellLinkPlugin/0.1.0");
        if (authenticated) builder.header("Authorization", "Bearer " + requireToken());
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_RESPONSE_BYTES) throw new IOException("Website response is too large");
        String payload = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Website returned HTTP " + response.statusCode() + errorMessage(payload));
        }
        return payload.isBlank() ? new JsonObject() : JsonParser.parseString(payload);
    }

    private String requireToken() {
        if (token == null || tokenExpiry == null || !tokenExpiry.isAfter(Instant.now())) {
            throw new IllegalStateException("JLShell Link account is not signed in");
        }
        return token;
    }

    private String requireDeviceRecord() throws Exception {
        if (deviceRecordId == null) registerDeviceIdentity();
        return deviceRecordId;
    }

    private URI requireBaseUri() {
        if (baseUri == null) throw new IllegalStateException("Website base URL is not configured");
        return baseUri;
    }

    private String deviceId() {
        String existing = secret(DEVICE_ID_KEY);
        if (existing != null) return existing;
        if (!secrets.available()) return "unavailable";
        String created = "desktop-" + random(24);
        putSecret(DEVICE_ID_KEY, created);
        return created;
    }

    private static String deviceName() {
        String user = System.getProperty("user.name", "user");
        String os = System.getProperty("os.name", "desktop");
        return (user + " · " + os).substring(0, Math.min(128, user.length() + os.length() + 3));
    }

    private void clearSession() {
        token = null;
        tokenExpiry = null;
        account = null;
        deviceRecordId = null;
        state = "SIGNED_OUT";
        if (secrets.available()) {
            secrets.remove(TOKEN_KEY);
            secrets.remove(TOKEN_EXPIRY_KEY);
            secrets.remove(ACCOUNT_KEY);
        }
    }

    private String secret(String key) {
        return secrets.available() ? secrets.get(key)
                .map(value -> new String(value, StandardCharsets.UTF_8)).orElse(null) : null;
    }

    private void putSecret(String key, String value) {
        secrets.put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static URI validateBaseUri(String value) {
        try {
            URI uri = URI.create(value.trim());
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()));
            if (!("https".equalsIgnoreCase(uri.getScheme()) || loopback)
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Website URL must be HTTPS (loopback HTTP is allowed for development)");
        }
    }

    private static void handleCallback(HttpExchange exchange, String expectedState,
                                       CompletableFuture<String> code) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        boolean valid = "GET".equals(exchange.getRequestMethod())
                && MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.US_ASCII),
                    query.getOrDefault("state", "").getBytes(StandardCharsets.US_ASCII))
                && query.containsKey("code");
        byte[] response = (valid ? "JLShell 登录成功，可以关闭此窗口。" : "JLShell 登录回调无效。")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(valid ? 200 : 400, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
        if (valid) code.complete(query.get("code"));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private static boolean openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String errorMessage(String payload) {
        try {
            JsonObject error = JsonParser.parseString(payload).getAsJsonObject();
            return error.has("message") ? ": " + error.get("message").getAsString() : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        HttpServer server = loginServer;
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
