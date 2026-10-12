package com.jlshell.link.plugin.program.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.model.CommandOutput;
import com.jlshell.plugin.api.model.RemoteFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedAgentPackageTest {
    @TempDir Path temporary;

    @Test void verifiesExactManifestAndArchiveBytesAndRejectsUnknownKeys() throws Exception {
        Fixture fixture = fixture("1.2.3", false);
        var verified = fixture.verify();
        assertThat(verified.version()).isEqualTo("1.2.3");
        assertThat(verified.publisherKeyId()).isEqualTo(SignedAgentPackage.digest(fixture.key().getPublic().getEncoded()));
        byte[] changed = fixture.manifest().clone(); changed[changed.length - 1] ^= 1;
        assertThatThrownBy(() -> SignedAgentPackage.verify(fixture.zip(), changed, fixture.signature(), fixture.keys()))
                .isInstanceOf(SecurityException.class);
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> SignedAgentPackage.verify(fixture.zip(), fixture.manifest(), fixture.signature(),
                List.of(Base64.getEncoder().encodeToString(other.getPublic().getEncoded())))).isInstanceOf(SecurityException.class);
        Files.writeString(fixture.zip(), "tampered");
        assertThatThrownBy(fixture::verify).isInstanceOf(SecurityException.class);
    }

    @Test void rejectsZipTraversalAndMissingTrust() throws Exception {
        Fixture unsafe = fixture("1.2.4", true);
        assertThatThrownBy(() -> JavaAgentDeploymentService.validateZip(unsafe.verify())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SignedAgentPackage.verify(unsafe.zip(), unsafe.manifest(), unsafe.signature(), List.of()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void ordersSemverAndRejectsMalformedVersions() {
        assertThat(SignedAgentPackage.compareVersions("1.2.3", "1.2.3-rc.10")).isPositive();
        assertThat(SignedAgentPackage.compareVersions("1.2.3-rc.2", "1.2.3-rc.10")).isNegative();
        assertThat(SignedAgentPackage.compareVersions("1.2.2", "1.2.3")).isNegative();
        assertThatThrownBy(() -> SignedAgentPackage.compareVersions("01.2.3", "1.2.3"))
                .isInstanceOf(SecurityException.class);
    }

    @Test void requiresConfirmationAndNeverPlacesEnrollmentSecretInCommands() throws Exception {
        Fixture fixture = fixture("1.2.5", false);
        var commands = new ArrayList<String>();
        var files = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        CommandExecutor executor = new CommandExecutor() {
            public CompletableFuture<CommandOutput> execute(String command) { return execute(command, java.time.Duration.ofSeconds(5)); }
            public CompletableFuture<CommandOutput> execute(String command, java.time.Duration timeout) {
                commands.add(command);
                if (command.startsWith("printf")) return CompletableFuture.completedFuture(
                        new CommandOutput("OS=Linux\nARCH=x86_64\nHOME=/home/test\n", "", 0));
                return CompletableFuture.completedFuture(new CommandOutput("", "", command.startsWith("test -f") ? 1 : 0));
            }
        };
        FileExplorer explorer = new FileExplorer() {
            public CompletableFuture<List<RemoteFile>> listDirectory(String path) { return CompletableFuture.completedFuture(List.of()); }
            public CompletableFuture<byte[]> readFile(String path) { return CompletableFuture.failedFuture(new java.io.IOException()); }
            public CompletableFuture<Void> writeFile(String path, byte[] value) { files.put(path, value.clone()); return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> deleteFile(String path) { files.remove(path); return CompletableFuture.completedFuture(null); }
        };
        SshSessionContext ssh = (SshSessionContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SshSessionContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "commandExecutor" -> executor;
                    case "fileExplorer" -> explorer;
                    default -> "test";
                });
        var deployment = new JavaAgentDeploymentService(ssh);
        var request = new JavaAgentDeploymentService.Request("https://website.example", "wss://link.example/link/v2/control",
                "/secure/identity.p12", "/secure/password", "/secure/targets", java.util.UUID.randomUUID().toString(), "one-time-secret");
        assertThatThrownBy(() -> deployment.install(fixture.verify(), fixture.keys(), request, false).join())
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
        assertThat(deployment.install(fixture.verify(), fixture.keys(), request, true).join()).contains("Java Agent");
        assertThat(commands).noneMatch(command -> command.contains("one-time-secret"));
        assertThat(commands).anyMatch(command -> command.contains("upgrade-user-service.sh"));
        assertThat(files.keySet()).noneMatch(path -> path.endsWith("enrollment.token"));
        assertThat(request.toString()).doesNotContain("one-time-secret");
    }

    private Fixture fixture(String version, boolean traversal) throws Exception {
        String base = "jlshell-link-agent-java-" + version + "-linux-x64";
        Path zip = temporary.resolve(base + ".zip");
        try (var stream = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String path : List.of("link-agent.jar", "runtime/bin/java", traversal ? "../outside" : "manifest.json")) {
                stream.putNextEntry(new ZipEntry(base + "/" + path)); stream.write(new byte[]{1, 2, 3}); stream.closeEntry();
            }
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1); manifest.addProperty("product", "jlshell-link-agent-java");
        manifest.addProperty("protocolVersion", "jlshell-link-v2"); manifest.addProperty("version", version);
        manifest.addProperty("platform", "linux"); manifest.addProperty("architecture", "x64");
        manifest.addProperty("sourceRevision", "a".repeat(40));
        JsonArray artifacts = new JsonArray();
        for (String extension : List.of(".zip", ".tar.gz")) {
            JsonObject artifact = new JsonObject(); artifact.addProperty("name", base + extension);
            artifact.addProperty("sizeBytes", Files.size(zip)); artifact.addProperty("sha256", SignedAgentPackage.digest(zip)); artifacts.add(artifact);
        }
        manifest.add("artifacts", artifacts);
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(bytes);
        JsonObject signature = new JsonObject(); signature.addProperty("schemaVersion", 1); signature.addProperty("algorithm", "Ed25519");
        signature.addProperty("keyId", SignedAgentPackage.digest(key.getPublic().getEncoded()));
        signature.addProperty("manifestSha256", SignedAgentPackage.digest(bytes));
        signature.addProperty("signature", Base64.getEncoder().encodeToString(signer.sign()));
        return new Fixture(zip, bytes, signature.toString().getBytes(StandardCharsets.UTF_8), key);
    }
    private record Fixture(Path zip, byte[] manifest, byte[] signature, KeyPair key) {
        List<String> keys() { return List.of(Base64.getEncoder().encodeToString(key.getPublic().getEncoded())); }
        SignedAgentPackage verify() throws Exception { return SignedAgentPackage.verify(zip, manifest, signature, keys()); }
    }
}
