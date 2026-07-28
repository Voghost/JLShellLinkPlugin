package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectorProcessManagerTest {

    @TempDir Path temporaryDirectory;

    @Test
    void probesIdentityStartsLoopbackTunnelAndStopsIt() throws Exception {
        Path binary = Files.writeString(temporaryDirectory.resolve("jlshell-connector"), "test");
        try {
            Files.setPosixFilePermissions(binary, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows does not expose POSIX permissions.
        }
        List<List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        ConnectorProcessManager.ProcessLauncher launcher = command -> {
            commands.add(List.copyOf(command));
            if (command.contains("--print-identity")) {
                return FakeProcess.finished("CONNECTOR_PEER_ID=12D3KooWConnector\n"
                        + "CONNECTOR_EVENT=IDENTITY_READY\n");
            }
            if (command.contains("--version")) {
                return FakeProcess.finished("jlshell-connector 0.1.0\n");
            }
            return FakeProcess.running("CONNECTION_PATH=DIRECT\n"
                    + "LISTEN_ADDRESS=127.0.0.1:43123\n"
                    + "CONNECTOR_EVENT=TUNNEL_LISTENING\n");
        };
        ConnectorConfiguration configuration = new ConnectorConfiguration(binary,
                temporaryDirectory.resolve("identity.key"), null);

        try (ConnectorProcessManager manager = new ConnectorProcessManager(configuration, launcher)) {
            for (int attempt = 0; attempt < 100 && !manager.status().get("available").getAsBoolean(); attempt++) {
                Thread.sleep(10);
            }
            assertThat(manager.status().get("state").getAsString()).isEqualTo("READY");

            JsonObject opened = manager.open(TunnelOpenRequestTest.validArgs()).join().getAsJsonObject();
            assertThat(opened.get("localAddress").getAsString()).isEqualTo("127.0.0.1:43123");
            assertThat(opened.get("connectionPath").getAsString()).isEqualTo("DIRECT");
            List<String> tunnelCommand = commands.stream().filter(value -> value.contains("--ticket"))
                    .findFirst().orElseThrow();
            assertThat(tunnelCommand).containsSubsequence("--local-bind", "127.0.0.1:0");
            Path ticket = Path.of(tunnelCommand.get(tunnelCommand.indexOf("--ticket") + 1));
            assertThat(ticket).doesNotExist();

            JsonObject close = new JsonObject();
            close.addProperty("tunnelId", opened.get("tunnelId").getAsString());
            assertThat(manager.close(close).join().getAsJsonObject().get("closed").getAsBoolean()).isTrue();
        }
    }

    private static final class FakeProcess extends Process {
        private final InputStream output;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean alive;

        private FakeProcess(String output, boolean running) {
            this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            alive = new AtomicBoolean(running);
            if (!running) {
                finished.countDown();
            }
        }

        static FakeProcess finished(String output) { return new FakeProcess(output, false); }
        static FakeProcess running(String output) { return new FakeProcess(output, true); }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { finished.await(); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException("process is alive");
            return 0;
        }
        @Override public void destroy() { alive.set(false); finished.countDown(); }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return alive.get(); }
    }
}
