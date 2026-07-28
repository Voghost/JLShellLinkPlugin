package com.jlshell.link.plugin.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.link.plugin.common.ProgramCapabilityClient;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.model.CommandOutput;
import com.jlshell.plugin.api.rpc.CapabilityBus;

final class AgentDeploymentService {

    private static final long MAX_AGENT_BYTES = 200L * 1024 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private final SshSessionContext ssh;
    private final CapabilityBus capabilityBus;

    AgentDeploymentService(SshSessionContext ssh, CapabilityBus capabilityBus) {
        this.ssh = ssh;
        this.capabilityBus = capabilityBus;
    }

    CompletableFuture<DeploymentResult> deploy() {
        return detectPlatform()
                .thenCompose(platform -> installSpec(platform)
                        .thenCompose(spec -> loadBinary(spec)
                                .thenCompose(binary -> upload(platform, spec, binary))));
    }

    CompletableFuture<RemotePlatform> detectPlatform() {
        String unix = "printf 'OS=%s\\nARCH=%s\\nHOME=%s\\n' \"$(uname -s)\" \"$(uname -m)\" \"$HOME\"";
        return ssh.commandExecutor().execute(unix, COMMAND_TIMEOUT).thenCompose(output -> {
            if (output.exitCode() == 0 && output.stdout().contains("OS=")) {
                try {
                    return CompletableFuture.completedFuture(RemotePlatform.parse(output));
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(error);
                }
            }
            String windows = "powershell -NoProfile -NonInteractive -Command \""
                    + "Write-Output ('OS=Windows'); "
                    + "Write-Output ('ARCH=' + $env:PROCESSOR_ARCHITECTURE); "
                    + "Write-Output ('HOME=' + $env:USERPROFILE)\"";
            return ssh.commandExecutor().execute(windows, COMMAND_TIMEOUT)
                    .thenApply(RemotePlatform::parse);
        });
    }

    private CompletableFuture<InstallSpec> installSpec(RemotePlatform platform) {
        JsonObject args = new JsonObject();
        args.addProperty("platform", platform.platform());
        args.addProperty("architecture", platform.architecture());
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                        LinkPluginContract.AGENT_INSTALL_SPEC_CAPABILITY, args)
                .thenApply(value -> {
                    JsonObject result = value.getAsJsonObject();
                    return new InstallSpec(Path.of(result.get("path").getAsString()),
                            result.get("size").getAsLong(), result.get("sha256").getAsString());
                });
    }

    private CompletableFuture<byte[]> loadBinary(InstallSpec spec) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.isRegularFile(spec.path()) || spec.size() < 1 || spec.size() > MAX_AGENT_BYTES) {
                    throw new IllegalStateException("Configured Agent binary is missing or too large");
                }
                byte[] binary = Files.readAllBytes(spec.path());
                String digest = sha256(binary);
                if (binary.length != spec.size() || !digest.equalsIgnoreCase(spec.sha256())) {
                    throw new IllegalStateException("Agent binary changed after install specification was resolved");
                }
                return binary;
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
    }

    private CompletableFuture<DeploymentResult> upload(RemotePlatform platform, InstallSpec spec, byte[] binary) {
        String directory = platform.remoteDirectory();
        String remoteBinary = platform.remoteBinary();
        String temporaryBinary = remoteBinary + ".tmp-" + java.util.UUID.randomUUID();
        String prepare = platform.windows()
                ? "powershell -NoProfile -NonInteractive -Command \"New-Item -ItemType Directory -Force "
                    + "-LiteralPath '" + powershellLiteral(directory) + "' | Out-Null\""
                : "mkdir -p " + shellQuote(directory);
        CompletableFuture<DeploymentResult> deployment = requireSuccess(
                ssh.commandExecutor().execute(prepare, COMMAND_TIMEOUT),
                "Cannot create remote Agent directory")
                .thenCompose(ignored -> ssh.fileExplorer().writeFile(temporaryBinary, binary))
                .thenCompose(ignored -> verifyRemote(platform, temporaryBinary, spec.sha256()))
                .thenCompose(ignored -> makeExecutable(platform, temporaryBinary))
                .thenCompose(ignored -> promote(platform, temporaryBinary, remoteBinary))
                .thenApply(ignored -> new DeploymentResult(platform.platform(), platform.architecture(),
                        remoteBinary, spec.sha256(), binary.length));
        return deployment.exceptionallyCompose(error ->
                ssh.fileExplorer().deleteFile(temporaryBinary)
                        .handle((ignored, cleanupError) -> (Void) null)
                        .thenCompose(ignored -> CompletableFuture.<DeploymentResult>failedFuture(error)));
    }

    private CompletableFuture<Void> verifyRemote(RemotePlatform platform, String remoteBinary, String expected) {
        String command = platform.windows()
                ? "powershell -NoProfile -NonInteractive -Command \"(Get-FileHash -Algorithm SHA256 "
                    + "-LiteralPath '" + powershellLiteral(remoteBinary) + "').Hash\""
                : "(command -v sha256sum >/dev/null 2>&1 && sha256sum " + shellQuote(remoteBinary)
                    + " || shasum -a 256 " + shellQuote(remoteBinary) + ")";
        return ssh.commandExecutor().execute(command, COMMAND_TIMEOUT).thenCompose(output -> {
            String actual = output.stdout().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (output.exitCode() != 0 || !actual.equals(expected.toLowerCase(Locale.ROOT))) {
                return ssh.fileExplorer().deleteFile(remoteBinary).handle((ignored, deleteError) -> {
                    throw new java.util.concurrent.CompletionException(
                            new IllegalStateException("Remote Agent SHA-256 verification failed"));
                });
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<Void> makeExecutable(RemotePlatform platform, String remoteBinary) {
        if (platform.windows()) {
            return CompletableFuture.completedFuture(null);
        }
        return requireSuccess(ssh.commandExecutor().execute(
                "chmod 700 " + shellQuote(remoteBinary), COMMAND_TIMEOUT),
                "Cannot mark remote Agent executable");
    }

    private CompletableFuture<Void> promote(RemotePlatform platform, String temporaryBinary, String remoteBinary) {
        String command = platform.windows()
                ? "powershell -NoProfile -NonInteractive -Command \"Move-Item -Force "
                    + "-LiteralPath '" + powershellLiteral(temporaryBinary) + "' -Destination '"
                    + powershellLiteral(remoteBinary) + "'\""
                : "mv -f " + shellQuote(temporaryBinary) + " " + shellQuote(remoteBinary);
        return requireSuccess(ssh.commandExecutor().execute(command, COMMAND_TIMEOUT),
                "Cannot activate uploaded Agent binary");
    }

    private static CompletableFuture<Void> requireSuccess(CompletableFuture<CommandOutput> future, String message) {
        return future.thenApply(output -> {
            if (output.exitCode() != 0) {
                throw new IllegalStateException(message + ": " + output.stderr().trim());
            }
            return null;
        });
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String powershellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record InstallSpec(Path path, long size, String sha256) {
    }

    record DeploymentResult(String platform, String architecture, String remotePath,
                            String sha256, long size) {
    }
}
