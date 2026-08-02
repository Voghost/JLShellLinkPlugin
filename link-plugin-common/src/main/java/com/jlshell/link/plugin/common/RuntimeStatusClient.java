package com.jlshell.link.plugin.common;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.jlshell.plugin.api.rpc.CapabilityBus;

public final class RuntimeStatusClient {

    private RuntimeStatusClient() {
    }

    public static CompletableFuture<JsonElement> query(CapabilityBus capabilityBus) {
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                LinkPluginContract.RUNTIME_STATUS_CAPABILITY, null);
    }
}
