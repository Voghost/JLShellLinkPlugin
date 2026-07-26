package com.jlshell.link.plugin.common;

import com.google.gson.JsonObject;

public final class LinkPluginContract {

    public static final String PROGRAM_PLUGIN_ID = "com.jlshell.link.program";
    public static final String SESSION_PLUGIN_ID = "com.jlshell.link.session";
    public static final String RUNTIME_STATUS_CAPABILITY = "link.runtime.status";
    public static final String VERSION = "0.1.0-SNAPSHOT";
    public static final String MIN_HOST_VERSION = "0.1.36";

    private LinkPluginContract() {
    }

    public static JsonObject notConfiguredStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("available", false);
        status.addProperty("state", "NOT_CONFIGURED");
        status.add("version", com.google.gson.JsonNull.INSTANCE);
        return status;
    }
}

