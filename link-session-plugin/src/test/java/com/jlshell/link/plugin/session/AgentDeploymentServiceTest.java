package com.jlshell.link.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.capability.InteractiveCommandExecutor;
import com.jlshell.plugin.api.capability.LogViewer;
import com.jlshell.plugin.api.capability.ServerStatusProvider;
import com.jlshell.plugin.api.model.CommandOutput;
import com.jlshell.plugin.api.model.RemoteFile;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDeploymentServiceTest {

    @TempDir Path temporaryDirectory;

    @Test
    void uploadsVerifiesAndProtectsAgentBinary() throws Exception {
        byte[] binary = new byte[]{1, 2, 3, 4, 5};
        Path local = Files.write(temporaryDirectory.resolve("jlshell-agent-linux-x64"), binary);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        AtomicReference<String> uploadedPath = new AtomicReference<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        TestSshSession ssh = new TestSshSession(sha256, uploadedPath, uploaded);
        CapabilityBus bus = new CapabilityBus() {
            @Override public CompletableFuture<RpcResponse> invoke(RpcRequest request) {
                assertThat(request.sessionId()).isNull();
                assertThat(request.capability()).isEqualTo(LinkPluginContract.AGENT_INSTALL_SPEC_CAPABILITY);
                JsonObject result = new JsonObject();
                result.addProperty("path", local.toString());
                result.addProperty("size", binary.length);
                result.addProperty("sha256", sha256);
                return CompletableFuture.completedFuture(RpcResponse.ok(result));
            }
            @Override public List<CapabilitySpec> listCapabilities(String sessionId) { return List.of(); }
            @Override public List<Capability> listRegisteredCapabilities(String sessionId) { return List.of(); }
        };

        AgentDeploymentService.DeploymentResult result = new AgentDeploymentService(ssh, bus).deploy().join();

        assertThat(result.platform()).isEqualTo("linux");
        assertThat(result.remotePath()).isEqualTo("/home/test/.jlshell-link/bin/jlshell-agent");
        assertThat(uploadedPath.get()).startsWith(result.remotePath() + ".tmp-");
        assertThat(uploaded.get()).containsExactly(binary);
        assertThat(ssh.commands).anyMatch(command -> command.startsWith("chmod 700 "));
        assertThat(ssh.commands).anyMatch(command -> command.startsWith("mv -f "));
    }

    private static final class TestSshSession implements SshSessionContext {
        private final String sha256;
        private final AtomicReference<String> uploadedPath;
        private final AtomicReference<byte[]> uploaded;
        private final List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();

        private TestSshSession(String sha256, AtomicReference<String> uploadedPath,
                               AtomicReference<byte[]> uploaded) {
            this.sha256 = sha256;
            this.uploadedPath = uploadedPath;
            this.uploaded = uploaded;
        }

        @Override public String sessionId() { return "session-1"; }
        @Override public String displayName() { return "test"; }
        @Override public String host() { return "127.0.0.1"; }
        @Override public int port() { return 22; }
        @Override public String username() { return "test"; }
        @Override public CommandExecutor commandExecutor() {
            return new CommandExecutor() {
                @Override public CompletableFuture<CommandOutput> execute(String command) {
                    return execute(command, Duration.ofSeconds(30));
                }
                @Override public CompletableFuture<CommandOutput> execute(String command, Duration timeout) {
                    commands.add(command);
                    if (command.startsWith("printf 'OS=")) {
                        return CompletableFuture.completedFuture(new CommandOutput(
                                "OS=Linux\nARCH=x86_64\nHOME=/home/test\n", "", 0));
                    }
                    if (command.contains("sha256sum")) {
                        return CompletableFuture.completedFuture(new CommandOutput(
                                sha256 + "  /home/test/.jlshell-link/bin/jlshell-agent\n", "", 0));
                    }
                    return CompletableFuture.completedFuture(new CommandOutput("", "", 0));
                }
            };
        }
        @Override public FileExplorer fileExplorer() {
            return new FileExplorer() {
                @Override public CompletableFuture<List<RemoteFile>> listDirectory(String path) {
                    return CompletableFuture.completedFuture(List.of());
                }
                @Override public CompletableFuture<byte[]> readFile(String path) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException());
                }
                @Override public CompletableFuture<Void> writeFile(String path, byte[] content) {
                    uploadedPath.set(path);
                    uploaded.set(content.clone());
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<Void> deleteFile(String path) {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
        @Override public InteractiveCommandExecutor interactiveCommandExecutor() { return null; }
        @Override public LogViewer logViewer() { return null; }
        @Override public ServerStatusProvider serverStatus() { return null; }
    }
}
