package com.jlshell.link.plugin.common;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;

public final class RuntimeStatusClient {

    private RuntimeStatusClient() {
    }

    public static CompletableFuture<JsonElement> query(CapabilityBus capabilityBus) {
        if (capabilityBus == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("JLShell global capability bus is unavailable"));
        }
        RpcRequest request = new RpcRequest(
                null,
                LinkPluginContract.PROGRAM_PLUGIN_ID,
                LinkPluginContract.RUNTIME_STATUS_CAPABILITY,
                JsonNull.INSTANCE,
                UUID.randomUUID().toString());
        return capabilityBus.invoke(request).thenApply(response -> {
            Objects.requireNonNull(response, "capability response");
            if (response.error() != null) {
                throw new IllegalStateException("runtime status failed: " + response.error().message());
            }
            return response.result() == null ? JsonNull.INSTANCE : response.result();
        });
    }
}

