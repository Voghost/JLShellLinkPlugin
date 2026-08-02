package com.jlshell.link.plugin.program;

import java.nio.file.Path;

import com.jlshell.plugin.api.storage.PluginStorage;

record ConnectorConfiguration(Path connectorBinary, Path identityFile, Path agentBundleDirectory) {

    private static final String CONNECTOR_BINARY_KEY = "connector.binary.path";
    private static final String IDENTITY_FILE_KEY = "connector.identity.path";
    private static final String AGENT_BUNDLE_KEY = "agent.bundle.directory";

    static ConnectorConfiguration load(PluginStorage storage) {
        Path defaultIdentity = Path.of(System.getProperty("user.home"), ".jlshell", "link",
                "connector-identity.key").toAbsolutePath().normalize();
        if (storage == null) {
            return new ConnectorConfiguration(null, defaultIdentity, null);
        }
        return new ConnectorConfiguration(
                path(storage.get(CONNECTOR_BINARY_KEY)),
                path(storage.get(IDENTITY_FILE_KEY, defaultIdentity.toString())),
                path(storage.get(AGENT_BUNDLE_KEY)));
    }

    void save(PluginStorage storage) {
        if (storage == null) {
            return;
        }
        putOrRemove(storage, CONNECTOR_BINARY_KEY, connectorBinary);
        putOrRemove(storage, IDENTITY_FILE_KEY, identityFile);
        putOrRemove(storage, AGENT_BUNDLE_KEY, agentBundleDirectory);
    }

    ConnectorConfiguration normalized() {
        return new ConnectorConfiguration(normalize(connectorBinary), normalize(identityFile),
                normalize(agentBundleDirectory));
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value.trim());
    }

    private static Path normalize(Path value) {
        return value == null ? null : value.toAbsolutePath().normalize();
    }

    private static void putOrRemove(PluginStorage storage, String key, Path value) {
        if (value == null) {
            storage.remove(key);
        } else {
            storage.put(key, value.toString());
        }
    }
}
