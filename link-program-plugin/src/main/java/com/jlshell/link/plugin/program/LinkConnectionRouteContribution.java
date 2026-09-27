package com.jlshell.link.plugin.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.link.core.ProtocolVersion;
import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.plugin.api.connection.ProgramConnectionRouteContribution;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the in-process Java Link client before SSH setup; no Connector process is involved in this route. */
final class LinkConnectionRouteContribution implements ProgramConnectionRouteContribution {
    private final LinkBindingStore bindings;
    private final LinkV2AccountClient account;
    private final LinkSubscriptionService subscriptions;
    private final LinkClientRuntime client;

    LinkConnectionRouteContribution(LinkBindingStore bindings, LinkV2AccountClient account,
            LinkSubscriptionService subscriptions, LinkClientRuntime client) {
        this.bindings = bindings;
        this.account = account;
        this.subscriptions = subscriptions;
        this.client = client;
    }

    @Override
    public boolean supports(ConnectionRouteRequest request) {
        return request.projectId() != null && bindings.getProject(request.projectId()) != null;
    }

    @Override
    public CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request) {
        JsonObject binding = bindings.getProject(request.projectId());
        if (binding == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("项目 Link Agent 绑定不存在"));
        }
        final UUID gateway;
        try { gateway = UUID.fromString(required(binding, "agentId")); }
        catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(new IllegalStateException("项目 Link Agent 绑定无效", invalid));
        }

        CompletableFuture<ConnectionRoute> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<?>> opening = new AtomicReference<>();
        result.whenComplete((route, error) -> {
            if (result.isCancelled()) {
                CompletableFuture<?> pending = opening.get();
                if (pending != null) pending.cancel(true);
            }
        });
        subscriptions.requireProgramAndSession("link.tcp-tunnel")
                .thenCompose(ignored -> account.agents())
                .whenComplete((agents, error) -> {
                    if (result.isDone()) return;
                    if (error != null) {
                        result.completeExceptionally(rootCause(error));
                        return;
                    }
                    try {
                        requireOnlineV2Agent(agents, gateway);
                    } catch (RuntimeException invalid) {
                        result.completeExceptionally(invalid);
                        return;
                    }
                    CompletableFuture<com.jlshell.link.client.LinkClientEngine.LocalTunnelLease> tunnel;
                    try { tunnel = client.openTunnel(gateway.toString(), request.host(), request.port()); }
                    catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    opening.set(tunnel);
                    if (result.isCancelled()) tunnel.cancel(true);
                    tunnel.whenComplete((lease, tunnelError) -> {
                        if (tunnelError != null) {
                            result.completeExceptionally(rootCause(tunnelError));
                            return;
                        }
                        ConnectionRoute route = ConnectionRoute.loopback(
                                lease.host(), lease.port(), lease, gateway.toString());
                        if (!result.complete(route)) lease.close();
                    });
                });
        return result;
    }

    private static void requireOnlineV2Agent(JsonArray agents, UUID gateway) {
        if (agents == null) throw new IllegalStateException("Website 未返回 Link Agent 目录");
        for (JsonElement entry : agents) {
            JsonObject agent = entry.getAsJsonObject();
            if (!gateway.toString().equals(string(agent, "agentId"))) continue;
            if (!"ONLINE".equals(string(agent, "state"))) {
                throw new IllegalStateException("项目绑定的 Java Link Agent 当前离线");
            }
            if (!ProtocolVersion.V2.equals(string(agent, "protocolVersion"))) {
                throw new IllegalStateException("项目绑定的 Agent 尚未升级到 Link v2");
            }
            if (string(agent, "nodeKeyFingerprint").isBlank()) {
                throw new IllegalStateException("Website 尚未绑定该 Agent 的安全身份");
            }
            return;
        }
        throw new IllegalStateException("项目绑定的 Link Agent 不属于当前账号或已被撤销");
    }

    private static String required(JsonObject value, String name) {
        String result = string(value, name).trim();
        if (result.isEmpty()) throw new IllegalStateException("项目 Link 绑定无效");
        return result;
    }

    private static String string(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : "";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
