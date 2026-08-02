package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class BundledRuntimeManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsVerifiedConnectorAndAllAgentTargets() throws Exception {
        Path resources = temporaryDirectory.resolve("resources");
        writeBundle(resources, false);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {resources.toUri().toURL()}, null)) {
            BundledRuntimeManager manager = new BundledRuntimeManager(
                    temporaryDirectory.resolve("installed"), loader,
                    new BundledRuntimeManager.HostPlatform("macos", "arm64"));

            BundledRuntimeManager.PreparedRuntime prepared = manager.prepare();

            assertThat(prepared).isNotNull();
            assertThat(prepared.connectorBinary()).hasFileName("jlshell-connector-macos-arm64");
            assertThat(prepared.agentBundleDirectory().resolve("jlshell-agent-linux-x64")).isRegularFile();
            assertThat(prepared.agentBundleDirectory().resolve("jlshell-agent-macos-arm64")).isRegularFile();
            assertThat(prepared.agentBundleDirectory().resolve("jlshell-agent-windows-x64.exe")).isRegularFile();
            assertThat(manager.status().get("state").getAsString()).isEqualTo("READY");
        }
    }

    @Test
    void rejectsTamperedRuntimeBeforeActivation() throws Exception {
        Path resources = temporaryDirectory.resolve("tampered-resources");
        writeBundle(resources, true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {resources.toUri().toURL()}, null)) {
            BundledRuntimeManager manager = new BundledRuntimeManager(
                    temporaryDirectory.resolve("tampered-installed"), loader,
                    new BundledRuntimeManager.HostPlatform("linux", "x64"));

            assertThat(manager.prepare()).isNull();
            assertThat(manager.status().get("state").getAsString()).isEqualTo("INVALID_BUNDLE");
            assertThat(Files.exists(temporaryDirectory.resolve("tampered-installed/1.0.0/bin/"
                    + "jlshell-connector-linux-x64"))).isFalse();
        }
    }

    @Test
    void explainsWhenDevelopmentJarHasNoNativeBundle() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[0], null)) {
            BundledRuntimeManager manager = new BundledRuntimeManager(
                    temporaryDirectory.resolve("missing"), loader,
                    new BundledRuntimeManager.HostPlatform("macos", "arm64"));

            assertThat(manager.prepare()).isNull();
            assertThat(manager.status().get("state").getAsString()).isEqualTo("BUNDLE_MISSING");
        }
    }

    private static void writeBundle(Path resources, boolean tamperConnector) throws Exception {
        Path root = resources.resolve(BundledRuntimeManager.RESOURCE_ROOT);
        Path binaries = root.resolve("bin");
        Files.createDirectories(binaries);
        JsonArray files = new JsonArray();
        add(files, binaries, "connector", "linux", "x64", "jlshell-connector-linux-x64", tamperConnector);
        add(files, binaries, "connector", "macos", "arm64", "jlshell-connector-macos-arm64", false);
        add(files, binaries, "connector", "windows", "x64", "jlshell-connector-windows-x64.exe", false);
        add(files, binaries, "agent", "linux", "x64", "jlshell-agent-linux-x64", false);
        add(files, binaries, "agent", "macos", "arm64", "jlshell-agent-macos-arm64", false);
        add(files, binaries, "agent", "windows", "x64", "jlshell-agent-windows-x64.exe", false);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1);
        manifest.addProperty("runtimeVersion", "1.0.0");
        manifest.add("files", files);
        Files.writeString(root.resolve("manifest.json"), manifest.toString(), StandardCharsets.UTF_8);
    }

    private static void add(JsonArray entries, Path directory, String role, String platform,
                            String architecture, String name, boolean tamperDigest) throws Exception {
        byte[] value = (role + ":" + platform + ":" + architecture).getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve(name), value);
        JsonObject entry = new JsonObject();
        entry.addProperty("role", role);
        entry.addProperty("platform", platform);
        entry.addProperty("architecture", architecture);
        entry.addProperty("path", "bin/" + name);
        entry.addProperty("size", value.length);
        entry.addProperty("sha256", tamperDigest ? "0".repeat(64) : sha256(value));
        entries.add(entry);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
