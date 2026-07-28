package com.jlshell.link.plugin.program;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class ConnectorProcessManager implements AutoCloseable {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_DIAGNOSTIC_LINES = 12;
    private static final int MAX_TUNNELS = 16;
    private final Map<UUID, ManagedTunnel> tunnels = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jlshell-link-connector");
        thread.setDaemon(true);
        return thread;
    });
    private final ProcessLauncher launcher;
    private final AtomicLong probeGeneration = new AtomicLong();
    private final java.util.concurrent.Semaphore tunnelSlots = new java.util.concurrent.Semaphore(MAX_TUNNELS);
    private volatile ConnectorConfiguration configuration;
    private volatile ProbeResult probe = ProbeResult.notConfigured();

    ConnectorProcessManager(ConnectorConfiguration configuration) {
        this(configuration, command -> new ProcessBuilder(command).redirectErrorStream(true).start());
    }

    ConnectorProcessManager(ConnectorConfiguration configuration, ProcessLauncher launcher) {
        this.configuration = configuration.normalized();
        this.launcher = launcher;
        refreshProbe();
    }

    void configure(ConnectorConfiguration value) {
        configuration = value.normalized();
        refreshProbe();
    }

    JsonObject status() {
        ProbeResult current = probe;
        JsonObject result = new JsonObject();
        result.addProperty("available", current.available());
        result.addProperty("state", current.state());
        if (current.version() == null) {
            result.add("version", com.google.gson.JsonNull.INSTANCE);
        } else {
            result.addProperty("version", current.version());
        }
        if (current.peerId() == null) {
            result.add("connectorPeerId", com.google.gson.JsonNull.INSTANCE);
        } else {
            result.addProperty("connectorPeerId", current.peerId());
        }
        result.addProperty("activeTunnels", tunnels.size());
        if (current.message() != null) {
            result.addProperty("message", current.message());
        }
        return result;
    }

    CompletableFuture<JsonElement> open(JsonElement args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (JsonElement) openBlocking(TunnelOpenRequest.parse(args));
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    CompletableFuture<JsonElement> close(JsonElement args) {
        return CompletableFuture.supplyAsync(() -> {
            if (args == null || !args.isJsonObject() || !args.getAsJsonObject().has("tunnelId")) {
                throw new CompletionException(new IllegalArgumentException("tunnelId is required"));
            }
            UUID tunnelId;
            try {
                tunnelId = UUID.fromString(args.getAsJsonObject().get("tunnelId").getAsString());
            } catch (RuntimeException error) {
                throw new CompletionException(new IllegalArgumentException("tunnelId is invalid", error));
            }
            ManagedTunnel tunnel = tunnels.get(tunnelId);
            boolean closed = tunnel != null && stop(tunnel.process());
            JsonObject result = new JsonObject();
            result.addProperty("tunnelId", tunnelId.toString());
            result.addProperty("closed", closed);
            return (JsonElement) result;
        }, executor);
    }

    Path agentBinary(String platform, String architecture) {
        Path directory = configuration.agentBundleDirectory();
        if (directory == null) {
            throw new IllegalStateException("Agent binary directory is not configured");
        }
        String file = switch (platform + "/" + architecture) {
            case "linux/x64" -> "jlshell-agent-linux-x64";
            case "macos/arm64" -> "jlshell-agent-macos-arm64";
            case "windows/x64" -> "jlshell-agent-windows-x64.exe";
            default -> throw new IllegalArgumentException("Unsupported Agent platform: "
                    + platform + "/" + architecture);
        };
        Path binary = directory.resolve(file).normalize();
        if (!binary.startsWith(directory) || !Files.isRegularFile(binary)) {
            throw new IllegalStateException("Agent binary is missing: " + file);
        }
        return binary;
    }

    private JsonObject openBlocking(TunnelOpenRequest request) throws Exception {
        if (!tunnelSlots.tryAcquire()) {
            throw new IllegalStateException("At most " + MAX_TUNNELS + " Connector tunnels may run at once");
        }
        java.util.concurrent.atomic.AtomicBoolean watcherOwnsSlot = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            return startTunnel(request, watcherOwnsSlot);
        } finally {
            if (!watcherOwnsSlot.get()) {
                tunnelSlots.release();
            }
        }
    }

    private JsonObject startTunnel(TunnelOpenRequest request,
                                   java.util.concurrent.atomic.AtomicBoolean watcherOwnsSlot) throws Exception {
        ConnectorConfiguration current = configuration;
        requireRunnable(current.connectorBinary());
        Path runtimeDirectory = runtimeDirectory(current.identityFile());
        Files.createDirectories(runtimeDirectory);
        secureDirectory(runtimeDirectory);
        UUID tunnelId = UUID.randomUUID();
        Path ticketFile = runtimeDirectory.resolve("ticket-" + tunnelId + ".pb");
        writeSecret(ticketFile, request.ticket());

        List<String> command = command(current, request, ticketFile);
        Process process;
        try {
            process = launcher.start(command);
        } catch (Exception error) {
            Files.deleteIfExists(ticketFile);
            throw error;
        }
        ManagedTunnel tunnel = new ManagedTunnel(tunnelId, process, ticketFile,
                new CompletableFuture<>(), new ArrayDeque<>());
        tunnels.put(tunnelId, tunnel);
        pumpOutput(tunnel);
        watchExit(tunnel);
        watcherOwnsSlot.set(true);
        Ready ready;
        try {
            ready = tunnel.ready().get(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!process.isAlive()) {
                throw new IllegalStateException("Connector exited before the tunnel could be used"
                        + diagnosticSuffix(tunnel.diagnostics()));
            }
        } catch (TimeoutException error) {
            stop(process);
            throw new IllegalStateException("Connector did not become ready within "
                    + READY_TIMEOUT.toSeconds() + " seconds", error);
        } finally {
            Files.deleteIfExists(ticketFile);
        }
        JsonObject result = new JsonObject();
        result.addProperty("tunnelId", tunnelId.toString());
        result.addProperty("localAddress", ready.localAddress());
        result.addProperty("connectionPath", ready.connectionPath());
        result.addProperty("state", "LISTENING");
        return result;
    }

    private static List<String> command(ConnectorConfiguration configuration,
                                        TunnelOpenRequest request, Path ticketFile) {
        List<String> command = new ArrayList<>();
        command.add(configuration.connectorBinary().toString());
        command.add("--identity");
        command.add(configuration.identityFile().toString());
        command.add("--agent-peer");
        command.add(request.agentPeer());
        for (String address : request.agentAddresses()) {
            command.add("--agent-address");
            command.add(address);
        }
        if (request.relayAddress() != null) {
            command.add("--relay-address");
            command.add(request.relayAddress());
            command.add("--relay-peer");
            command.add(request.relayPeer());
        }
        command.add("--connect-policy");
        command.add(request.connectPolicy());
        command.add("--ticket");
        command.add(ticketFile.toString());
        command.add("--target");
        command.add(request.targetIp().contains(":")
                ? "[" + request.targetIp() + "]:" + request.targetPort()
                : request.targetIp() + ":" + request.targetPort());
        command.add("--local-bind");
        command.add("127.0.0.1:0");
        return List.copyOf(command);
    }

    private void pumpOutput(ManagedTunnel tunnel) {
        executor.execute(() -> {
            String connectionPath = "UNKNOWN";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    tunnel.process().getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    remember(tunnel.diagnostics(), line);
                    if (line.startsWith("CONNECTION_PATH=")) {
                        connectionPath = line.substring("CONNECTION_PATH=".length());
                    } else if (line.startsWith("LISTEN_ADDRESS=")) {
                        tunnel.ready().complete(new Ready(
                                line.substring("LISTEN_ADDRESS=".length()), connectionPath));
                    }
                }
            } catch (IOException error) {
                tunnel.ready().completeExceptionally(error);
            }
        });
    }

    private void watchExit(ManagedTunnel tunnel) {
        executor.execute(() -> {
            try {
                int exitCode = tunnel.process().waitFor();
                if (!tunnel.ready().isDone()) {
                    tunnel.ready().completeExceptionally(new IllegalStateException(
                            "Connector exited with code " + exitCode + diagnosticSuffix(tunnel.diagnostics())));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                tunnel.ready().completeExceptionally(error);
            } finally {
                tunnels.remove(tunnel.id(), tunnel);
                tunnelSlots.release();
                try {
                    Files.deleteIfExists(tunnel.ticketFile());
                } catch (IOException ignored) {
                    // Best-effort cleanup; the runtime directory itself remains private.
                }
            }
        });
    }

    private void refreshProbe() {
        long generation = probeGeneration.incrementAndGet();
        ConnectorConfiguration current = configuration;
        if (!runnable(current.connectorBinary()) || current.identityFile() == null) {
            probe = ProbeResult.notConfigured();
            return;
        }
        probe = new ProbeResult(false, "PROBING", null, null, null);
        CompletableFuture.runAsync(() -> {
            ProbeResult result = probe(current);
            if (probeGeneration.get() == generation) {
                probe = result;
            }
        }, executor);
    }

    private ProbeResult probe(ConnectorConfiguration current) {
        try {
            Path runtimeDirectory = runtimeDirectory(current.identityFile());
            Files.createDirectories(runtimeDirectory);
            secureDirectory(runtimeDirectory);
            Process process = launcher.start(List.of(
                    current.connectorBinary().toString(),
                    "--identity", current.identityFile().toString(),
                    "--print-identity"));
            List<String> lines = readProcess(process, Duration.ofSeconds(10));
            String peerId = lines.stream().filter(line -> line.startsWith("CONNECTOR_PEER_ID="))
                    .map(line -> line.substring("CONNECTOR_PEER_ID=".length())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Connector did not report its PeerId"));
            Process versionProcess = launcher.start(List.of(current.connectorBinary().toString(), "--version"));
            String version = readProcess(versionProcess, Duration.ofSeconds(5)).stream().findFirst().orElse(null);
            return new ProbeResult(true, "READY", version, peerId, null);
        } catch (Exception error) {
            return new ProbeResult(false, "ERROR", null, null, rootMessage(error));
        }
    }

    private static List<String> readProcess(Process process, Duration timeout) throws Exception {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            stop(process);
            throw new TimeoutException("Process timed out");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().limit(32).toList();
        }
    }

    private static boolean stop(Process process) {
        if (!process.isAlive()) {
            return false;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return true;
    }

    private static Path runtimeDirectory(Path identityFile) {
        Path parent = identityFile.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Connector identity path must have a parent directory");
        }
        return parent.resolve("runtime").normalize();
    }

    private static void requireRunnable(Path binary) {
        if (!runnable(binary)) {
            throw new IllegalStateException("Connector binary is not configured or executable");
        }
    }

    private static boolean runnable(Path binary) {
        return binary != null && Files.isRegularFile(binary)
                && (isWindows() || Files.isExecutable(binary));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void secureDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs are inherited from the user's profile directory.
        }
    }

    private static void writeSecret(Path path, byte[] content) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(path);
        }
        Files.write(path, content, java.nio.file.StandardOpenOption.WRITE);
    }

    private static synchronized void remember(ArrayDeque<String> diagnostics, String line) {
        if (diagnostics.size() == MAX_DIAGNOSTIC_LINES) {
            diagnostics.removeFirst();
        }
        diagnostics.addLast(line);
    }

    private static synchronized String diagnosticSuffix(ArrayDeque<String> diagnostics) {
        return diagnostics.isEmpty() ? "" : ": " + String.join(" | ", diagnostics);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        tunnels.values().forEach(tunnel -> stop(tunnel.process()));
        tunnels.clear();
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private record ManagedTunnel(UUID id, Process process, Path ticketFile,
                                 CompletableFuture<Ready> ready, ArrayDeque<String> diagnostics) {
    }

    private record Ready(String localAddress, String connectionPath) {
    }

    private record ProbeResult(boolean available, String state, String version,
                               String peerId, String message) {
        static ProbeResult notConfigured() {
            return new ProbeResult(false, "NOT_CONFIGURED", null, null, null);
        }
    }
}
