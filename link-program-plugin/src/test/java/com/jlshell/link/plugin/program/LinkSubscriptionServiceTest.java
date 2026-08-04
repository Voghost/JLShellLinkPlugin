package com.jlshell.link.plugin.program;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.jlshell.program.api.AccountRequest;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class LinkSubscriptionServiceTest {
    @Test
    void readsEntitlementsAndPoliciesOnlyThroughHostGateway() {
        AccountSessionService host = new AccountSessionService() {
            @Override public AccountSession snapshot() {
                return new AccountSession(true, "https://jlshell.oomn.net", "device", "account", "alice", "a@b.c", "USER", "2030-01-01T00:00:00Z");
            }
            @Override public CompletableFuture<JsonElement> request(AccountRequest request) {
                if (request.path().equals("/api/v1/account/entitlements")) return json("{\"entitlements\":[\"link.tcp-tunnel\",\"link.agent-deploy\"]}");
                return json("{\"allowed\":true,\"reason\":\"ALLOWED\"}");
            }
        };
        LinkSubscriptionService service = new LinkSubscriptionService(host);
        assertThat(service.refresh().join().getAsJsonObject().get("state").getAsString()).isEqualTo("READY");
        assertThat(service.requireSession("link.tcp-tunnel").join()).isNotNull();
    }

    private static CompletableFuture<JsonElement> json(String value) {
        return CompletableFuture.completedFuture(JsonParser.parseString(value));
    }
}
