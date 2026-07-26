package com.jlshell.link.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import org.junit.jupiter.api.Test;

class JlShellLinkSessionPluginTest {

    @Test
    void serviceLoaderDiscoversSessionPlugin() {
        assertThat(ServiceLoader.load(JlShellPlugin.class).stream()
                .map(provider -> provider.get().id()))
                .contains(LinkPluginContract.SESSION_PLUGIN_ID);
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

        JsonElement result = new JlShellLinkSessionPlugin().loadRuntimeStatus(bus).join();

        assertThat(captured.get().sessionId()).isNull();
        assertThat(captured.get().pluginId()).isEqualTo(LinkPluginContract.PROGRAM_PLUGIN_ID);
        assertThat(captured.get().capability()).isEqualTo(LinkPluginContract.RUNTIME_STATUS_CAPABILITY);
        assertThat(result.getAsJsonObject().get("state").getAsString()).isEqualTo("NOT_CONFIGURED");
    }
}

