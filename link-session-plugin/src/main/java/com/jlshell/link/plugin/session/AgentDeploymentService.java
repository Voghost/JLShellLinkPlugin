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

    CompletableFuture<ProvisioningResult> deployAndRegister(String agentName) {
        return detectPlatform().thenCompose(platform -> installSpec(platform)
                .thenCompose(spec -> loadBinary(spec)
                        .thenCompose(binary -> upload(platform, spec, binary))
                        .thenCompose(deployed -> identity(platform, deployed)
                                .thenCompose(identity -> challenge(identity)
                                        .thenCompose(challenge -> proof(platform, deployed, challenge)
                                                .thenCompose(signature -> register(agentName, platform, identity,
                                                        challenge, signature)
                                                        .thenCompose(registration -> configureAndStart(platform,
                                                                deployed, registration))))))));
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

    private CompletableFuture<AgentIdentity> identity(RemotePlatform platform, DeploymentResult deployed) {
        String identityFile = platform.remoteDirectory() + "/agent-identity.key";
        String command = shellCommand(platform, deployed.remotePath(),
                "--identity", identityFile, "--print-identity");
        return ssh.commandExecutor().execute(command, COMMAND_TIMEOUT).thenApply(output -> {
            if (output.exitCode() != 0) throw new IllegalStateException("Cannot initialize Agent identity");
            return new AgentIdentity(identityFile, outputValue(output, "AGENT_PEER_ID"),
                    outputValue(output, "AGENT_PUBLIC_KEY"));
        });
    }

    private CompletableFuture<JsonObject> challenge(AgentIdentity identity) {
        JsonObject args = new JsonObject();
        args.addProperty("publicKey", identity.publicKey());
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                        LinkPluginContract.AGENT_CHALLENGE_CAPABILITY, args)
                .thenApply(value -> value.getAsJsonObject());
    }

    private CompletableFuture<String> proof(RemotePlatform platform, DeploymentResult deployed,
                                            JsonObject challenge) {
        String command = shellCommand(platform, deployed.remotePath(),
                "--identity", platform.remoteDirectory() + "/agent-identity.key",
                "--identity-proof", challenge.get("payload").getAsString());
        return ssh.commandExecutor().execute(command, COMMAND_TIMEOUT).thenApply(output -> {
            if (output.exitCode() != 0) throw new IllegalStateException("Agent identity proof failed");
            return outputValue(output, "AGENT_PROOF_SIGNATURE");
        });
    }

    private CompletableFuture<JsonObject> register(String name, RemotePlatform platform, AgentIdentity identity,
                                                   JsonObject challenge, String signature) {
        JsonObject args = new JsonObject();
        args.addProperty("name", name == null || name.isBlank() ? "JLShell Agent" : name.substring(0, Math.min(120, name.length())));
        args.addProperty("platform", platform.platform());
        args.addProperty("architecture", platform.architecture());
        args.addProperty("publicKey", identity.publicKey());
        args.addProperty("version", "0.1.0");
        args.addProperty("challengeId", challenge.get("challengeId").getAsString());
        args.addProperty("proofSignature", signature);
        args.addProperty("targetIp", "127.0.0.1");
        args.addProperty("targetPort", 22);
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                        LinkPluginContract.AGENT_REGISTER_CAPABILITY, args)
                .thenApply(value -> value.getAsJsonObject());
    }

    private CompletableFuture<ProvisioningResult> configureAndStart(RemotePlatform platform,
                                                                    DeploymentResult deployed,
                                                                    JsonObject registration) {
        return ProgramCapabilityClient.invoke(capabilityBus, null,
                        LinkPluginContract.AUTHORITY_CAPABILITY, new JsonObject())
                .thenCombine(ProgramCapabilityClient.invoke(capabilityBus, null,
                        LinkPluginContract.ACCOUNT_STATUS_CAPABILITY, new JsonObject()),
                        (authority, account) -> new RuntimeFiles(authority.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                registration.get("credential").getAsString(),
                                account.getAsJsonObject().get("baseUrl").getAsString(), null, null))
                .thenCombine(ProgramCapabilityClient.invoke(capabilityBus, null,
                        LinkPluginContract.LINK_CATALOG_CAPABILITY, new JsonObject()),
                        AgentDeploymentService::withRelay)
                .thenCompose(files -> writeRuntimeFiles(platform, files)
                        .thenCompose(ignored -> startAgent(platform, deployed, files))
                        .thenApply(serviceManager -> {
                            JsonObject agent = registration.getAsJsonObject("agent");
                            return new ProvisioningResult(deployed.platform(), deployed.architecture(),
                                    deployed.remotePath(), agent.get("id").getAsString(),
                                    agent.get("peerId").getAsString(), "127.0.0.1", 22,
                                    serviceManager);
                        }));
    }

    private CompletableFuture<Void> writeRuntimeFiles(RemotePlatform platform, RuntimeFiles files) {
        String credential = platform.remoteDirectory() + "/agent-token";
        String authority = platform.remoteDirectory() + "/authority.json";
        return writeSecret(platform, credential,
                files.credential().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .thenCompose(ignored -> writeSecret(platform, authority, files.authority()));
    }

    private CompletableFuture<Void> writeSecret(RemotePlatform platform, String destination, byte[] value) {
        String temporary = destination + ".tmp-" + java.util.UUID.randomUUID();
        CompletableFuture<Void> result = ssh.fileExplorer().writeFile(temporary, value);
        if (!platform.windows()) {
            result = result.thenCompose(ignored -> requireSuccess(ssh.commandExecutor().execute(
                    "chmod 600 " + shellQuote(temporary), COMMAND_TIMEOUT), "Cannot protect Agent runtime file"));
        }
        return result.thenCompose(ignored -> promote(platform, temporary, destination))
                .exceptionallyCompose(error -> ssh.fileExplorer().deleteFile(temporary)
                        .handle((ignored, cleanupError) -> (Void) null)
                        .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
    }

    private CompletableFuture<String> startAgent(
            RemotePlatform platform, DeploymentResult deployed, RuntimeFiles files) {
        return new AgentServiceInstaller(ssh).install(platform, deployed.remotePath(), files.baseUrl(),
                files.relayAddress(), files.relayPeer());
    }

    private static RuntimeFiles withRelay(RuntimeFiles files, com.google.gson.JsonElement catalogValue) {
        com.google.gson.JsonArray relays = catalogValue.getAsJsonObject().getAsJsonArray("relays");
        if (relays == null || relays.isEmpty()) return files;
        JsonObject relay = java.util.stream.StreamSupport.stream(relays.spliterator(), false)
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(value -> "ONLINE".equals(value.get("state").getAsString()))
                .findFirst().orElse(null);
        if (relay == null) return files;
        return new RuntimeFiles(files.authority(), files.credential(), files.baseUrl(),
                relay.get("endpoint").getAsString(), relay.get("peerId").getAsString());
    }

    private static String shellCommand(RemotePlatform platform, String executable, String... arguments) {
        if (platform.windows()) {
            String joined = java.util.Arrays.stream(arguments).map(AgentDeploymentService::powershellArgument)
                    .collect(java.util.stream.Collectors.joining(" "));
            return "powershell -NoProfile -NonInteractive -Command \"& '"
                    + powershellLiteral(executable) + "' " + joined + "\"";
        }
        return shellQuote(executable) + " " + java.util.Arrays.stream(arguments)
                .map(AgentDeploymentService::shellQuote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String outputValue(CommandOutput output, String name) {
        return output.stdout().lines().filter(line -> line.startsWith(name + "="))
                .map(line -> line.substring(name.length() + 1).trim()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Agent omitted " + name));
    }

    private static String powershellArgument(String value) {
        return "'" + powershellLiteral(value) + "'";
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

    record ProvisioningResult(String platform, String architecture, String remotePath,
                              String agentId, String agentPeerId, String targetIp, int targetPort,
                              String serviceManager) { }

    private record AgentIdentity(String identityFile, String peerId, String publicKey) { }
    private record RuntimeFiles(byte[] authority, String credential, String baseUrl,
                                String relayAddress, String relayPeer) { }
}
