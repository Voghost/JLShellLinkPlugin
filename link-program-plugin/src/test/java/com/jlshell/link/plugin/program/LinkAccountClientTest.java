package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jlshell.program.api.AccountRequest;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkAccountClientTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exposesOnlyHostSessionMetadataAndNeverStoresAnAccessToken() {
        FakeHostAccount host = new FakeHostAccount(true);
        try (ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
             LinkAccountClient client = new LinkAccountClient(host, connector)) {
            JsonObject status = client.status();

            assertThat(status.get("state").getAsString()).isEqualTo("AUTHENTICATED");
            assertThat(status.get("baseUrl").getAsString()).isEqualTo("https://jlshell.oomn.net");
            assertThat(status.toString()).doesNotContain("token");
            assertThat(status.getAsJsonObject("account").get("username").getAsString()).isEqualTo("alice");
        }
    }

    @Test
    void directsSignedOutUsersToTheHostAccountLogin() {
        try (ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
             LinkAccountClient client = new LinkAccountClient(new FakeHostAccount(false), connector)) {
            assertThat(client.status().get("state").getAsString()).isEqualTo("SIGNED_OUT");
            assertThat(client.authority()).failsWithin(java.time.Duration.ofSeconds(1))
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withMessageContaining("账号设置");
        }
    }

    @Test
    void forwardsLinkRequestsThroughTheHostGateway() throws Exception {
        FakeHostAccount host = new FakeHostAccount(true);
        try (ConnectorProcessManager connector = new ConnectorProcessManager(
                new ConnectorConfiguration(null, temporaryDirectory.resolve("identity.key"), null));
             LinkAccountClient client = new LinkAccountClient(host, connector)) {
            client.authority().get();

            assertThat(host.request.get().method()).isEqualTo("GET");
            assertThat(host.request.get().path()).isEqualTo("/api/v1/link/ticket-authority");
        }
    }

    @Test
    void retriesDeviceIdentityRegistrationAfterTransientFailure() throws Exception {
        RecoveringHostAccount host = new RecoveringHostAccount(true);
        try (LinkAccountClient client = new LinkAccountClient(host, connectorIdentity())) {
            assertThat(client.issueTicket(ticketRequest())).failsWithin(java.time.Duration.ofSeconds(1));

            JsonObject ticket = client.issueTicket(ticketRequest()).get().getAsJsonObject();

            assertThat(ticket.get("ticket").getAsString()).isEqualTo("signed-ticket");
            assertThat(host.identityAttempts).hasValue(2);
            assertThat(host.ticketRequests).hasValue(1);
        }
    }

    @Test
    void reRegistersWhenWebsiteClearsTheStoredPeerIdentity() throws Exception {
        RecoveringHostAccount host = new RecoveringHostAccount(false);
        try (LinkAccountClient client = new LinkAccountClient(host, connectorIdentity())) {
            client.issueTicket(ticketRequest()).get();
            host.identityRegistered.set(false);

            client.issueTicket(ticketRequest()).get();

            assertThat(host.identityAttempts).hasValue(2);
            assertThat(host.ticketRequests).hasValue(2);
        }
    }

    @Test
    void catalogDoesNotRequireConnectorIdentityRegistration() throws Exception {
        RecoveringHostAccount host = new RecoveringHostAccount(false);
        try (LinkAccountClient client = new LinkAccountClient(host, connectorIdentity())) {
            JsonObject catalog = client.catalog().get().getAsJsonObject();

            assertThat(catalog.getAsJsonArray("agents")).isEmpty();
            assertThat(catalog.get("deviceId").getAsString()).isEqualTo("device-record-1");
            assertThat(host.identityAttempts).hasValue(0);
        }
    }

    private static LinkAccountClient.ConnectorIdentity connectorIdentity() {
        return new LinkAccountClient.ConnectorIdentity() {
            @Override public String peerId() { return "connector-peer"; }
            @Override public String publicKey() { return "connector-public-key"; }
            @Override public String signChallenge(String payload) { return "proof-for-" + payload; }
        };
    }

    private static JsonObject ticketRequest() {
        JsonObject request = new JsonObject();
        request.addProperty("agentId", "agent-1");
        request.addProperty("targetIp", "192.168.31.212");
        request.addProperty("targetPort", 22);
        return request;
    }

    private static final class FakeHostAccount implements AccountSessionService {
        private final boolean authenticated;
        private final AtomicReference<AccountRequest> request = new AtomicReference<>();

        private FakeHostAccount(boolean authenticated) {
            this.authenticated = authenticated;
        }

        @Override public AccountSession snapshot() {
            return authenticated
                    ? new AccountSession(true, "https://jlshell.oomn.net", "device-1", "account-1",
                    "alice", "alice@example.com", "user", "2030-01-01T00:00:00Z")
                    : AccountSession.signedOut("https://jlshell.oomn.net", "device-1");
        }

        @Override public CompletableFuture<JsonElement> request(AccountRequest request) {
            this.request.set(request);
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }

    private static final class RecoveringHostAccount implements AccountSessionService {
        private final boolean failFirstIdentityRegistration;
        private final AtomicBoolean identityRegistered = new AtomicBoolean();
        private final AtomicInteger identityAttempts = new AtomicInteger();
        private final AtomicInteger ticketRequests = new AtomicInteger();

        private RecoveringHostAccount(boolean failFirstIdentityRegistration) {
            this.failFirstIdentityRegistration = failFirstIdentityRegistration;
        }

        @Override public AccountSession snapshot() {
            return new AccountSession(true, "https://jlshell.oomn.net", "device-1", "account-1",
                    "alice", "alice@example.com", "user", "2030-01-01T00:00:00Z");
        }

        @Override public CompletableFuture<JsonElement> request(AccountRequest request) {
            if ("GET".equals(request.method()) && "/api/v1/link/agents".equals(request.path())) {
                return CompletableFuture.completedFuture(new JsonArray());
            }
            if ("GET".equals(request.method()) && "/api/v1/link/relays".equals(request.path())) {
                return CompletableFuture.completedFuture(new JsonArray());
            }
            if ("GET".equals(request.method()) && "/api/v1/account/devices".equals(request.path())) {
                JsonObject device = new JsonObject();
                device.addProperty("id", "device-record-1");
                device.addProperty("deviceId", "device-1");
                if (identityRegistered.get()) {
                    device.addProperty("peerId", "connector-peer");
                } else {
                    device.add("peerId", JsonNull.INSTANCE);
                }
                JsonArray devices = new JsonArray();
                devices.add(device);
                return CompletableFuture.completedFuture(devices);
            }
            if ("POST".equals(request.method()) && "/api/v1/link/node-challenges".equals(request.path())) {
                JsonObject challenge = new JsonObject();
                challenge.addProperty("challengeId", "challenge-1");
                challenge.addProperty("payload", "challenge-payload");
                return CompletableFuture.completedFuture(challenge);
            }
            if ("PUT".equals(request.method()) && request.path().endsWith("/identity")) {
                int attempt = identityAttempts.incrementAndGet();
                if (failFirstIdentityRegistration && attempt == 1) {
                    return CompletableFuture.failedFuture(new IllegalStateException("temporary registration failure"));
                }
                identityRegistered.set(true);
                return CompletableFuture.completedFuture(JsonNull.INSTANCE);
            }
            if ("POST".equals(request.method()) && "/api/v1/link/tickets".equals(request.path())) {
                ticketRequests.incrementAndGet();
                JsonObject ticket = new JsonObject();
                ticket.addProperty("ticket", "signed-ticket");
                return CompletableFuture.completedFuture(ticket);
            }
            return CompletableFuture.failedFuture(new AssertionError("Unexpected request: " + request));
        }
    }
}
