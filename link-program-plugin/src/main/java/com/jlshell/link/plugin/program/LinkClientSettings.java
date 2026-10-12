package com.jlshell.link.plugin.program;

import com.jlshell.link.core.model.ConnectPolicy;
import com.jlshell.link.transport.Ice4jDirectSession;
import com.jlshell.plugin.api.storage.PluginStorage;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Public Link server settings. No authentication material is stored here. */
final class LinkClientSettings {
    static final String RELAY_URI_KEY = "link.v2.relay-uri";
    static final String STUN_SERVERS_KEY = "link.v2.stun-servers";
    static final String CONNECT_POLICY_KEY = "link.v2.connect-policy";
    static final String DEFAULT_RELAY_URI = "wss://jlink.oomn.net/link/v2/relay";

    private LinkClientSettings() { }

    static String relayUri(PluginStorage storage) {
        if (storage == null) return DEFAULT_RELAY_URI;
        String value = storage.get(RELAY_URI_KEY, DEFAULT_RELAY_URI);
        return validateRelayUri(value).toString();
    }

    static URI validateRelayUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Link Relay 地址不能为空");
        URI uri;
        try { uri = URI.create(value.strip()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Link Relay 地址格式无效", invalid); }
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !"/link/v2/relay".equals(uri.getPath())) {
            throw new IllegalArgumentException("Link Relay 地址必须是 wss://主机/link/v2/relay");
        }
        return uri;
    }

    static void saveRelayUri(PluginStorage storage, String value) {
        Objects.requireNonNull(storage, "storage").put(RELAY_URI_KEY, validateRelayUri(value).toString());
    }

    static String stunServers(PluginStorage storage) {
        return storage == null ? "" : storage.get(STUN_SERVERS_KEY, "");
    }

    static void saveStunServers(PluginStorage storage, String value) {
        Objects.requireNonNull(storage, "storage").put(STUN_SERVERS_KEY, parseStunServers(value).stream()
                .map(LinkClientSettings::formatStunServer).collect(java.util.stream.Collectors.joining(",")));
    }

    static ConnectPolicy connectPolicy(PluginStorage storage) {
        if (storage == null) return ConnectPolicy.AUTO;
        String configured = storage.get(CONNECT_POLICY_KEY, ConnectPolicy.AUTO.name());
        try { return ConnectPolicy.valueOf(configured.strip().toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException invalid) { return ConnectPolicy.AUTO; }
    }

    static void saveConnectPolicy(PluginStorage storage, ConnectPolicy policy) {
        Objects.requireNonNull(storage, "storage").put(CONNECT_POLICY_KEY,
                Objects.requireNonNull(policy, "policy").name());
    }

    static Ice4jDirectSession.Config iceConfig(PluginStorage storage) {
        return new Ice4jDirectSession.Config(parseStunServers(stunServers(storage)), 16,
                Duration.ofSeconds(8), 1_200, 100);
    }

    private static List<InetSocketAddress> parseStunServers(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] entries = value.split(",", -1);
        if (entries.length > 4) throw new IllegalArgumentException("最多配置 4 个 STUN 服务器");
        List<InetSocketAddress> result = new ArrayList<>(entries.length);
        for (String raw : entries) {
            String entry = raw.strip();
            String address;
            String portText;
            if (entry.startsWith("[")) {
                int close = entry.indexOf(']');
                if (close < 0 || close + 1 >= entry.length() || entry.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("STUN 地址必须使用数值 IP:端口格式");
                }
                address = entry.substring(1, close);
                portText = entry.substring(close + 2);
            } else {
                int separator = entry.lastIndexOf(':');
                if (separator <= 0 || entry.indexOf(':') != separator) {
                    throw new IllegalArgumentException("IPv6 STUN 地址请写成 [地址]:端口");
                }
                address = entry.substring(0, separator);
                portText = entry.substring(separator + 1);
            }
            int port;
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("STUN 端口无效"); }
            if (port < 1 || port > 65_535) throw new IllegalArgumentException("STUN 端口范围无效");
            result.add(new InetSocketAddress(parseNumericAddress(address), port));
        }
        return List.copyOf(result);
    }

    private static InetAddress parseNumericAddress(String value) {
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("STUN 地址必须是数值 IP");
            try {
                InetAddress address = InetAddress.getByName(value);
                if (!(address instanceof java.net.Inet6Address)) throw new IllegalArgumentException("STUN IPv6 地址无效");
                return address;
            } catch (UnknownHostException invalid) {
                throw new IllegalArgumentException("STUN IPv6 地址无效");
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("STUN 地址必须是数值 IPv4 或 IPv6");
        byte[] bytes = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            if (!octets[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("STUN IPv4 地址无效");
            int octet = Integer.parseInt(octets[i]);
            if (octet > 255) throw new IllegalArgumentException("STUN IPv4 地址无效");
            bytes[i] = (byte) octet;
        }
        try { return InetAddress.getByAddress(bytes); }
        catch (UnknownHostException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String formatStunServer(InetSocketAddress server) {
        String host = server.getAddress().getHostAddress();
        return server.getAddress() instanceof java.net.Inet6Address
                ? "[" + host + "]:" + server.getPort() : host + ":" + server.getPort();
    }
}
