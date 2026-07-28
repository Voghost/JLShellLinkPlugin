package com.jlshell.link.plugin.common;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;

public final class ProgramCapabilityClient {

    private ProgramCapabilityClient() {
    }

    public static CompletableFuture<JsonElement> invoke(CapabilityBus capabilityBus, String sessionId,
                                                         String capability, JsonElement args) {
        if (capabilityBus == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("JLShell global capability bus is unavailable"));
        }
        RpcRequest request = new RpcRequest(
                sessionId,
                LinkPluginContract.PROGRAM_PLUGIN_ID,
                capability,
                args == null ? JsonNull.INSTANCE : args,
                UUID.randomUUID().toString());
        return capabilityBus.invoke(request).thenApply(response -> {
            Objects.requireNonNull(response, "capability response");
            if (response.error() != null) {
                throw new IllegalStateException(capability + " failed: " + response.error().message());
            }
            return response.result() == null ? JsonNull.INSTANCE : response.result();
        });
    }
}
