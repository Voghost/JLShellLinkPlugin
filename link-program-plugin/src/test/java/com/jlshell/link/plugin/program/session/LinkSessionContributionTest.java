package com.jlshell.link.plugin.program.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import org.junit.jupiter.api.Test;

class LinkSessionContributionTest {

    @Test
    void exposesSingleProgramOwnedSessionContribution() {
        LinkSessionContribution contribution = new LinkSessionContribution();

        assertThat(contribution.displayName()).isEqualTo("JLShell Link");
        assertThat(contribution.description()).contains("Agent");
    }

    @Test
    void invokesProgramCapabilityInGlobalScope() {
        AtomicReference<RpcRequest> captured = new AtomicReference<>();
        CapabilityBus bus = new CapabilityBus() {
            @Override
            public CompletableFuture<RpcResponse> invoke(RpcRequest request) {
                captured.set(request);
                JsonElement status = LinkPluginContract.notConfiguredStatus();
                return CompletableFuture.completedFuture(RpcResponse.ok(status));
            }

            @Override public List<CapabilitySpec> listCapabilities(String sessionId) { return List.of(); }
            @Override public List<Capability> listRegisteredCapabilities(String sessionId) { return List.of(); }
        };

        JsonElement result = LinkSessionController.loadRuntimeStatus(bus).join();

        assertThat(captured.get().sessionId()).isNull();
        assertThat(captured.get().pluginId()).isEqualTo(LinkPluginContract.PROGRAM_PLUGIN_ID);
        assertThat(captured.get().capability()).isEqualTo(LinkPluginContract.RUNTIME_STATUS_CAPABILITY);
        assertThat(result.getAsJsonObject().get("state").getAsString()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void queriesProjectIntentThroughProgramScope() {
        AtomicReference<RpcRequest> captured = new AtomicReference<>();
        CapabilityBus bus = new CapabilityBus() {
            @Override public CompletableFuture<RpcResponse> invoke(RpcRequest request) {
                captured.set(request);
                var result = new com.google.gson.JsonObject();
                result.addProperty("requested", true);
                return CompletableFuture.completedFuture(RpcResponse.ok(result));
            }
            @Override public List<CapabilitySpec> listCapabilities(String sessionId) { return List.of(); }
            @Override public List<Capability> listRegisteredCapabilities(String sessionId) { return List.of(); }
        };

        JsonElement result = LinkSessionController.loadProjectIntent(bus, "session-1").join();

        assertThat(result.getAsJsonObject().get("requested").getAsBoolean()).isTrue();
        assertThat(captured.get().sessionId()).isNull();
        assertThat(captured.get().capability()).isEqualTo(LinkPluginContract.PROJECT_AGENT_INTENT_CAPABILITY);
        assertThat(captured.get().args().getAsJsonObject().get("sessionId").getAsString())
                .isEqualTo("session-1");
    }

    @Test
    void buildsTunnelArgumentsWithoutPuttingTicketOnACommandLine() {
        var args = LinkSessionController.tunnelArgs(
                "12D3KooWAbcdefghijkmnopqrstuvwxyz123456789",
                "/ip4/127.0.0.1/tcp/7001\n/ip6/::1/udp/7001/quic-v1",
                "", "", "auto", "AQID", "127.0.0.1", "22");

        assertThat(args.getAsJsonArray("agentAddresses")).hasSize(2);
        assertThat(args.get("targetPort").getAsInt()).isEqualTo(22);
        assertThat(args.get("ticket").getAsString()).isEqualTo("AQID");
    }
}
