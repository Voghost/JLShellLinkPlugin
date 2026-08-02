package com.jlshell.link.plugin.program;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 验证并解包随 Program 插件发布的三平台 Connector/Agent 运行时。 */
final class BundledRuntimeManager {

    static final String RESOURCE_ROOT = "META-INF/jlshell-link/runtime/";
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_BINARY_BYTES = 200L * 1024 * 1024;
    private static final Set<String> REQUIRED_PLATFORMS = Set.of(
            "linux/x64", "macos/arm64", "windows/x64");

    private final Path runtimeRoot;
    private final ClassLoader resources;
    private final HostPlatform host;
    private volatile PreparedRuntime prepared;
    private volatile RuntimeState state = new RuntimeState(false, "NOT_PREPARED", null,
            "正在准备内置运行时。", null);

    BundledRuntimeManager() {
        this(Path.of(System.getProperty("user.home"), ".jlshell", "link", "runtime"),
                BundledRuntimeManager.class.getClassLoader(), HostPlatform.detect());
    }

    BundledRuntimeManager(Path runtimeRoot, ClassLoader resources, HostPlatform host) {
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.resources = resources;
        this.host = host;
    }

    synchronized PreparedRuntime prepare() {
        try {
            Manifest manifest = readManifest();
            Path versionRoot = runtimeRoot.resolve(manifest.runtimeVersion()).normalize();
            if (!versionRoot.startsWith(runtimeRoot)) {
                throw new IOException("运行时版本目录越界");
            }
            Path binaryDirectory = versionRoot.resolve("bin");
            Files.createDirectories(binaryDirectory);
            secureDirectory(runtimeRoot);
            secureDirectory(versionRoot);
            secureDirectory(binaryDirectory);

            Path connector = null;
            Set<String> agents = new java.util.HashSet<>();
            Set<String> connectors = new java.util.HashSet<>();
            for (RuntimeFile file : manifest.files()) {
                Path installed = extract(file, binaryDirectory);
                if (file.role().equals("connector")) {
                    connectors.add(file.platform() + "/" + file.architecture());
                    if (file.matches(host)) connector = installed;
                } else {
                    agents.add(file.platform() + "/" + file.architecture());
                }
            }
            if (connector == null) {
                throw new IOException("内置运行时不支持当前平台 " + host.platform()
                        + "/" + host.architecture());
            }
            if (!connectors.containsAll(REQUIRED_PLATFORMS)) {
                throw new IOException("内置运行时缺少 Connector 平台："
                        + REQUIRED_PLATFORMS.stream().filter(value -> !connectors.contains(value))
                                .sorted().toList());
            }
            if (!agents.containsAll(REQUIRED_PLATFORMS)) {
                throw new IOException("内置运行时缺少 Agent 平台："
                        + REQUIRED_PLATFORMS.stream().filter(value -> !agents.contains(value))
                                .sorted().toList());
            }
            prepared = new PreparedRuntime(connector, binaryDirectory, manifest.runtimeVersion());
            state = new RuntimeState(true, "READY", manifest.runtimeVersion(),
                    "内置 Connector 与三平台 Agent 已校验。", connector);
            return prepared;
        } catch (Exception error) {
            prepared = null;
            state = new RuntimeState(false, error instanceof MissingBundleException
                    ? "BUNDLE_MISSING" : "INVALID_BUNDLE", null, rootMessage(error), null);
            return null;
        }
    }

    PreparedRuntime prepared() {
        return prepared;
    }

    JsonObject status() {
        RuntimeState current = state;
        JsonObject result = new JsonObject();
        result.addProperty("available", current.available());
        result.addProperty("state", current.state());
        if (current.version() == null) result.add("version", com.google.gson.JsonNull.INSTANCE);
        else result.addProperty("version", current.version());
        result.addProperty("platform", host.platform());
        result.addProperty("architecture", host.architecture());
        result.addProperty("message", current.message());
        if (current.connector() == null) result.add("connectorPath", com.google.gson.JsonNull.INSTANCE);
        else result.addProperty("connectorPath", current.connector().toString());
        return result;
    }

    private Manifest readManifest() throws IOException {
        try (InputStream input = resources.getResourceAsStream(RESOURCE_ROOT + "manifest.json")) {
            if (input == null) {
                throw new MissingBundleException("插件包未包含原生运行时，请安装正式完整发行版。");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                throw new IOException("不支持的内置运行时清单版本");
            }
            String runtimeVersion = required(root, "runtimeVersion");
            if (!runtimeVersion.matches("[A-Za-z0-9._-]+")) {
                throw new IOException("内置运行时版本格式无效");
            }
            JsonArray files = root.getAsJsonArray("files");
            if (files == null || files.isEmpty() || files.size() > 16) {
                throw new IOException("内置运行时文件清单无效");
            }
            List<RuntimeFile> entries = new ArrayList<>();
            for (JsonElement value : files) {
                JsonObject file = value.getAsJsonObject();
                RuntimeFile entry = new RuntimeFile(required(file, "role"),
                        required(file, "platform"), required(file, "architecture"),
                        required(file, "path"), file.get("size").getAsLong(), required(file, "sha256"));
                entry.validate();
                entries.add(entry);
            }
            return new Manifest(runtimeVersion, List.copyOf(entries));
        } catch (MissingBundleException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("无法读取内置运行时清单", error);
        }
    }

    private Path extract(RuntimeFile file, Path binaryDirectory) throws Exception {
        String name = Path.of(file.path()).getFileName().toString();
        Path destination = binaryDirectory.resolve(name).normalize();
        if (!destination.startsWith(binaryDirectory)) {
            throw new IOException("内置运行时目标路径越界");
        }
        if (valid(destination, file)) {
            secureExecutable(destination);
            return destination;
        }
        Path temporary = binaryDirectory.resolve("." + name + "." + UUID.randomUUID() + ".part");
        try (InputStream input = resources.getResourceAsStream(RESOURCE_ROOT + file.path())) {
            if (input == null) throw new IOException("插件包缺少内置文件 " + file.path());
            Files.copy(input, temporary);
        }
        if (!valid(temporary, file)) {
            Files.deleteIfExists(temporary);
            throw new IOException("内置运行时摘要不匹配：" + name);
        }
        secureExecutable(temporary);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        secureExecutable(destination);
        return destination;
    }

    private static boolean valid(Path file, RuntimeFile expected) throws Exception {
        return Files.isRegularFile(file) && Files.size(file) == expected.size()
                && sha256(file).equalsIgnoreCase(expected.sha256());
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void secureDirectory(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACL 由用户配置目录继承；不伪造 POSIX 权限。
        }
    }

    private static void secureExecutable(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setExecutable(true, true);
        }
    }

    private static String required(JsonObject object, String name) throws IOException {
        if (!object.has(name) || object.get(name).isJsonNull()) throw new IOException(name + " 缺失");
        String value = object.get(name).getAsString().trim();
        if (value.isEmpty()) throw new IOException(name + " 为空");
        return value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    record PreparedRuntime(Path connectorBinary, Path agentBundleDirectory, String version) { }

    record HostPlatform(String platform, String architecture) {
        static HostPlatform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String platform = os.contains("win") ? "windows" : os.contains("mac") ? "macos"
                    : os.contains("linux") ? "linux" : "unsupported";
            String architecture = arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                    : arch.equals("amd64") || arch.equals("x86_64") ? "x64" : "unsupported";
            return new HostPlatform(platform, architecture);
        }
    }

    private record Manifest(String runtimeVersion, List<RuntimeFile> files) { }

    private record RuntimeFile(String role, String platform, String architecture,
                               String path, long size, String sha256) {
        void validate() throws IOException {
            if (!(role.equals("connector") || role.equals("agent"))) {
                throw new IOException("未知运行时角色 " + role);
            }
            if (!(platform.equals("linux") || platform.equals("macos") || platform.equals("windows"))
                    || !(architecture.equals("x64") || architecture.equals("arm64"))) {
                throw new IOException("未知运行时平台 " + platform + "/" + architecture);
            }
            if (!path.matches("bin/[A-Za-z0-9._-]+") || path.contains("..")) {
                throw new IOException("运行时资源路径无效");
            }
            if (size < 1 || size > MAX_BINARY_BYTES || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("运行时文件大小或摘要无效");
            }
        }

        boolean matches(HostPlatform host) {
            return platform.equals(host.platform()) && architecture.equals(host.architecture());
        }
    }

    private record RuntimeState(boolean available, String state, String version,
                                String message, Path connector) { }

    private static final class MissingBundleException extends IOException {
        private MissingBundleException(String message) {
            super(message);
        }
    }
}
