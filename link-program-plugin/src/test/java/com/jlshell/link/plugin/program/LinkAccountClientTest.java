package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
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
}
