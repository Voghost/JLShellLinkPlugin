package com.jlshell.link.plugin.program;

import com.jlshell.plugin.api.storage.PluginStorage;
import java.net.URI;
import java.util.Objects;

/** Public Link server settings. No authentication material is stored here. */
final class LinkClientSettings {
    static final String RELAY_URI_KEY = "link.v2.relay-uri";
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
}
