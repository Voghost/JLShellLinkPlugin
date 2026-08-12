package com.jlshell.link.plugin.program;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

record TunnelOpenRequest(String agentPeer, List<String> agentAddresses, String relayAddress,
                         String relayPeer, String connectPolicy, byte[] ticket,
                         String targetIp, int targetPort) {

    private static final int MAX_TICKET_BYTES = 64 * 1024;

    static TunnelOpenRequest parse(JsonElement args) {
        if (args == null || !args.isJsonObject()) {
            throw new IllegalArgumentException("Tunnel arguments must be a JSON object");
        }
        JsonObject value = args.getAsJsonObject();
        String agentPeer = peer(requiredString(value, "agentPeer"), "agentPeer");
        List<String> addresses = strings(value.get("agentAddresses"), "agentAddresses").stream()
                .map(address -> multiaddr(address, "agentAddresses"))
                .toList();
        String relayAddress = optionalString(value, "relayAddress");
        String relayPeer = optionalString(value, "relayPeer");
        if ((relayAddress == null) != (relayPeer == null)) {
            throw new IllegalArgumentException("relayAddress and relayPeer must be supplied together");
        }
        if (relayAddress != null) {
            relayAddress = multiaddr(relayAddress, "relayAddress");
            relayPeer = peer(relayPeer, "relayPeer");
        }
        String policy = optionalString(value, "connectPolicy");
        policy = policy == null ? "auto" : policy;
        if (!List.of("auto", "direct-only", "relay-only").contains(policy)) {
            throw new IllegalArgumentException("connectPolicy is invalid");
        }
        if ("direct-only".equals(policy) && addresses.isEmpty()) {
            throw new IllegalArgumentException("direct-only requires at least one agent address");
        }
        if ("relay-only".equals(policy) && relayAddress == null) {
            throw new IllegalArgumentException("relay-only requires a relay");
        }
        byte[] ticket = decodeTicket(requiredString(value, "ticket"));
        String targetIp = exactIp(requiredString(value, "targetIp"));
        int targetPort = requiredPort(value, "targetPort");
        return new TunnelOpenRequest(agentPeer, addresses, relayAddress, relayPeer, policy,
                ticket, targetIp, targetPort);
    }

    private static List<String> strings(JsonElement value, String name) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 16) {
            throw new IllegalArgumentException(name + " must be an array with at most 16 entries");
        }
        List<String> result = new ArrayList<>();
        value.getAsJsonArray().forEach(entry -> {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " must contain strings");
            }
            result.add(entry.getAsString());
        });
        return List.copyOf(result);
    }

    private static String requiredString(JsonObject value, String name) {
        String result = optionalString(value, name);
        if (result == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return result;
    }

    private static String optionalString(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        String result = element.getAsString().trim();
        return result.isEmpty() ? null : result;
    }

    private static String peer(String value, String name) {
        try {
            return PeerIdCodec.toBase58(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " is not a valid PeerId encoding");
        }
    }

    private static String multiaddr(String value, String name) {
        String address = value.trim();
        if (address.length() > 500 || address.contains("@")
                || !(address.startsWith("/ip4/") || address.startsWith("/ip6/"))) {
            throw new IllegalArgumentException(name + " must contain IP multiaddrs only");
        }
        return address;
    }

    private static byte[] decodeTicket(String value) {
        try {
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(value);
            } catch (IllegalArgumentException ignored) {
                decoded = Base64.getDecoder().decode(value);
            }
            if (decoded.length == 0 || decoded.length > MAX_TICKET_BYTES) {
                throw new IllegalArgumentException("ticket size is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("ticket must be valid base64/base64url", error);
        }
    }

    private static String exactIp(String value) {
        String ip = value.trim();
        if (ip.contains(":") && ip.matches("[0-9A-Fa-f:.]+")) {
            try {
                java.net.InetAddress parsed = java.net.InetAddress.getByName(ip);
                if (!(parsed instanceof java.net.Inet6Address) || parsed.isAnyLocalAddress()) {
                    throw new IllegalArgumentException("targetIp must be an exact, non-unspecified IP address");
                }
                return ip;
            } catch (java.net.UnknownHostException error) {
                throw new IllegalArgumentException("targetIp must be an exact IP address", error);
            }
        }
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("targetIp must be an exact IP address");
        }
        for (String octet : octets) {
            try {
                if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException("targetIp must be an exact IP address");
                }
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("targetIp must be an exact IP address", error);
            }
        }
        if ("0.0.0.0".equals(ip)) {
            throw new IllegalArgumentException("targetIp must be an exact, non-unspecified IP address");
        }
        return ip;
    }

    private static int requiredPort(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " is required");
        }
        int port = element.getAsInt();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return port;
    }
}
