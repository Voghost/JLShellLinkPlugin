package com.jlshell.link.plugin.program;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.plugin.api.storage.PluginStorage;

final class LinkBindingStore {
    private static final String PREFIX = "binding.connection.";
    private static final String PROJECT_PREFIX = "binding.project.";
    private final PluginStorage storage;

    LinkBindingStore(PluginStorage storage) {
        this.storage = storage;
    }

    JsonObject get(SessionReference session) {
        JsonObject result = sessionJson(session);
        if (storage == null || session.connectionId() == null) {
            result.add("binding", com.google.gson.JsonNull.INSTANCE);
            return result;
        }
        String value = storage.get(key(session.connectionId()));
        if (value == null) {
            result.add("binding", com.google.gson.JsonNull.INSTANCE);
            return result;
        }
        try {
            result.add("binding", JsonParser.parseString(value).getAsJsonObject());
        } catch (RuntimeException error) {
            storage.remove(key(session.connectionId()));
            result.add("binding", com.google.gson.JsonNull.INSTANCE);
        }
        return result;
    }

    JsonObject save(SessionReference session, String agentId, String targetIp, int targetPort) {
        if (storage == null || session.connectionId() == null) {
            throw new IllegalStateException("Current session is not backed by a saved connection");
        }
        JsonObject binding = new JsonObject();
        nullable(binding, "projectId", session.projectId());
        binding.addProperty("connectionId", session.connectionId());
        binding.addProperty("agentId", agentId);
        binding.addProperty("targetIp", targetIp);
        binding.addProperty("targetPort", targetPort);
        storage.put(key(session.connectionId()), binding.toString());
        return get(session);
    }

    void removeProject(String projectId) {
        if (storage == null) return;
        storage.remove(projectKey(projectId));
        for (String key : storage.keys()) {
            if (!key.startsWith(PREFIX)) continue;
            try {
                JsonObject value = JsonParser.parseString(storage.get(key)).getAsJsonObject();
                if (value.has("projectId") && !value.get("projectId").isJsonNull()
                        && projectId.equals(value.get("projectId").getAsString())) {
                    storage.remove(key);
                }
            } catch (RuntimeException error) {
                storage.remove(key);
            }
        }
    }

    JsonObject getProject(String projectId) {
        if (storage == null || projectId == null) return null;
        return parse(storage.get(projectKey(projectId)));
    }

    void saveProject(String projectId, JsonObject binding) {
        if (storage == null || projectId == null) return;
        if (binding == null) {
            storage.remove(projectKey(projectId));
        } else {
            storage.put(projectKey(projectId), binding.toString());
        }
    }

    private static JsonObject sessionJson(SessionReference session) {
        JsonObject result = new JsonObject();
        result.addProperty("available", session.connectionId() != null);
        nullable(result, "projectId", session.projectId());
        nullable(result, "connectionId", session.connectionId());
        return result;
    }

    private static void nullable(JsonObject object, String name, String value) {
        if (value == null) object.add(name, com.google.gson.JsonNull.INSTANCE);
        else object.addProperty(name, value);
    }

    private static String key(String connectionId) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(connectionId.getBytes(StandardCharsets.UTF_8));
    }

    private static String projectKey(String projectId) {
        return PROJECT_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(projectId.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject parse(String value) {
        if (value == null) return null;
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (RuntimeException error) {
            return null;
        }
    }

    record SessionReference(String projectId, String connectionId) { }
}
