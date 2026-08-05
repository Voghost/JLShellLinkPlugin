package com.jlshell.link.plugin.program;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.plugin.api.connection.ProgramConnectionRouteContribution;

/** 在 SSH 建连前为项目绑定的 Agent 签发票据并启动本地 Connector 隧道。 */
final class LinkConnectionRouteContribution implements ProgramConnectionRouteContribution {
    private final LinkBindingStore bindings;
    private final LinkAccountClient account;
    private final LinkSubscriptionService subscriptions;
    private final ConnectorProcessManager connector;

    LinkConnectionRouteContribution(LinkBindingStore bindings, LinkAccountClient account,
                                    LinkSubscriptionService subscriptions, ConnectorProcessManager connector) {
        this.bindings = bindings;
        this.account = account;
        this.subscriptions = subscriptions;
        this.connector = connector;
    }

    @Override
    public boolean supports(ConnectionRouteRequest request) {
        return request.projectId() != null && bindings.getProject(request.projectId()) != null;
    }

    @Override
    public CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request) {
        JsonObject binding = bindings.getProject(request.projectId());
        if (binding == null) return CompletableFuture.failedFuture(new IllegalStateException("Link project binding is missing"));
        return subscriptions.requireProgram("link.tcp-tunnel")
                .thenCompose(ignored -> account.catalog())
                .thenCompose(catalog -> open(request, binding, catalog.getAsJsonObject()));
    }

    private CompletableFuture<ConnectionRoute> open(ConnectionRouteRequest request, JsonObject binding, JsonObject catalog) {
        JsonObject agent = agent(catalog.getAsJsonArray("agents"), required(binding, "agentId"));
        if (!"ONLINE".equals(agent.get("state").getAsString())) {
            return CompletableFuture.failedFuture(new IllegalStateException("项目绑定的 Link Agent 当前离线"));
        }
        String targetIp = request.host();
        int targetPort = request.port();
        if (!targetExists(agent.getAsJsonArray("targets"), targetIp, targetPort)) {
            return CompletableFuture.failedFuture(new IllegalStateException("当前 SSH 主机或端口未在该 Link Agent 的精确目标授权列表中"));
        }
        JsonObject ticketRequest = new JsonObject();
        ticketRequest.addProperty("agentId", agent.get("id").getAsString());
        ticketRequest.addProperty("targetIp", targetIp);
        ticketRequest.addProperty("targetPort", targetPort);
        return account.issueTicket(ticketRequest).thenCompose(ticket -> {
            JsonObject tunnel = new JsonObject();
            tunnel.addProperty("agentPeer", agent.get("peerId").getAsString());
            tunnel.add("agentAddresses", agent.has("addresses") ? agent.get("addresses").deepCopy() : new JsonArray());
            tunnel.addProperty("connectPolicy", "auto");
            JsonObject relay = firstRelay(catalog.getAsJsonArray("relays"));
            if (relay != null) {
                tunnel.addProperty("relayAddress", relay.get("endpoint").getAsString());
                tunnel.addProperty("relayPeer", relay.get("peerId").getAsString());
            }
            tunnel.addProperty("ticket", ticket.getAsJsonObject().get("ticket").getAsString());
            tunnel.addProperty("targetIp", targetIp);
            tunnel.addProperty("targetPort", targetPort);
            return connector.open(tunnel).thenApply(result -> route(result.getAsJsonObject(), request));
        });
    }

    private ConnectionRoute route(JsonObject opened, ConnectionRouteRequest request) {
        String tunnelId = opened.get("tunnelId").getAsString();
        InetSocketAddress local;
        try {
            String address = opened.get("localAddress").getAsString();
            int separator = address.lastIndexOf(':');
            local = new InetSocketAddress(address.substring(0, separator), Integer.parseInt(address.substring(separator + 1)));
        } catch (RuntimeException error) {
            close(tunnelId);
            throw new IllegalStateException("Connector returned an invalid local address", error);
        }
        if (!local.getAddress().isLoopbackAddress()) {
            close(tunnelId);
            throw new IllegalStateException("Connector must listen only on loopback");
        }
        String host = local.getAddress().getHostAddress().contains(":") ? "::1" : "127.0.0.1";
        return ConnectionRoute.loopback(host, local.getPort(), () -> close(tunnelId));
    }

    private void close(String tunnelId) {
        JsonObject request = new JsonObject(); request.addProperty("tunnelId", tunnelId);
        connector.close(request).exceptionally(error -> null).join();
    }

    private static JsonObject agent(JsonArray agents, String agentId) {
        for (JsonElement candidate : agents) {
            JsonObject agent = candidate.getAsJsonObject();
            if (agentId.equals(agent.get("id").getAsString())) return agent;
        }
        throw new IllegalStateException("项目绑定的 Link Agent 不属于当前账号或已被吊销");
    }

    private static boolean targetExists(JsonArray targets, String targetIp, int targetPort) {
        for (JsonElement candidate : targets) {
            JsonObject target = candidate.getAsJsonObject();
            if (target.get("enabled").getAsBoolean() && targetIp.equals(target.get("targetIp").getAsString())
                    && targetPort == target.get("targetPort").getAsInt()) return true;
        }
        return false;
    }

    private static JsonObject firstRelay(JsonArray relays) {
        if (relays == null) return null;
        for (JsonElement candidate : relays) {
            JsonObject relay = candidate.getAsJsonObject();
            if (relay.has("endpoint") && relay.has("peerId")) return relay;
        }
        return null;
    }

    private static String required(JsonObject value, String name) {
        if (!value.has(name) || value.get(name).isJsonNull()) throw new IllegalStateException("项目 Link 绑定无效");
        String result = value.get(name).getAsString().trim();
        if (result.isEmpty()) throw new IllegalStateException("项目 Link 绑定无效");
        return result;
    }
}
