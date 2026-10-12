package com.jlshell.link.plugin.program.session;

import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.model.CommandOutput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

/** Java-only deployment over the explicitly selected existing SSH session. */
public final class JavaAgentDeploymentService {
    private final SshSessionContext ssh;
    public JavaAgentDeploymentService(SshSessionContext ssh) { this.ssh = java.util.Objects.requireNonNull(ssh); }

    /** Secrets are never stored or interpolated into commands. */
    public record Request(String website, String wss, String identityPath, String passwordPath,
            String targetsPath, String agentId, String enrollmentToken, String stunServers) {
        public Request(String website, String wss, String identityPath, String passwordPath,
                String targetsPath, String agentId, String enrollmentToken) {
            this(website, wss, identityPath, passwordPath, targetsPath, agentId, enrollmentToken, "");
        }
        public Request {
            requireHttps(website);
            var uri = java.net.URI.create(wss);
            if (!"wss".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !"/link/v2/control".equals(uri.getPath())) {
                throw new IllegalArgumentException("需要 wss://主机/link/v2/control 地址");
            }
            for (String path : List.of(identityPath, passwordPath, targetsPath)) {
                if (path.isBlank() || path.length() > 1000 || path.chars().anyMatch(c -> c < 32)) {
                    throw new IllegalArgumentException("远端配置文件路径无效");
                }
            }
            stunServers = stunServers == null ? "" : stunServers;
            if (stunServers.length() > 512 || !stunServers.matches("[0-9a-fA-F:.,\\[\\]]*")) {
                throw new IllegalArgumentException("STUN 配置必须是数值地址和端口");
            }
            enrollmentToken = enrollmentToken == null ? "" : enrollmentToken;
            if (!enrollmentToken.isEmpty()) {
                UUID.fromString(agentId);
                if (enrollmentToken.length() > 4096 || enrollmentToken.chars().anyMatch(c -> c < 32)) {
                    throw new IllegalArgumentException("一次性注册令牌无效");
                }
            }
        }
        @Override public String toString() { return "JavaAgentDeploymentRequest[<redacted>]"; }
    }

    public CompletableFuture<String> install(SignedAgentPackage artifact, List<String> trust, Request request,
            boolean confirmed) {
        if (!confirmed) return CompletableFuture.failedFuture(new IllegalArgumentException("安装需要用户确认"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Reverify on every attempt, including offline files changed after selection.
                SignedAgentPackage checked = SignedAgentPackage.verify(artifact.archive(), artifact.manifest(),
                        artifact.signatureEnvelope(), trust);
                validateZip(checked);
                return checked;
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }).thenCompose(checked -> detect().thenCompose(platform -> {
            if (!checked.platform().equals(platform.platform()) || !checked.architecture().equals(platform.architecture())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Agent 包与当前 SSH 主机平台不符"));
            }
            return checkInstalled(platform, checked, trust).thenCompose(ignored -> deploy(platform, checked, request));
        }));
    }

    private CompletableFuture<JavaRemotePlatform> detect() {
        return ssh.commandExecutor().execute("printf 'OS=%s\\nARCH=%s\\nHOME=%s\\n' \"$(uname -s)\" \"$(uname -m)\" \"$HOME\"",
                Duration.ofSeconds(15)).thenCompose(output -> {
            if (output.exitCode() == 0 && output.stdout().contains("OS=")) {
                try { return CompletableFuture.completedFuture(JavaRemotePlatform.parse(output)); }
                catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
            }
            return ssh.commandExecutor().execute(ps("Write-Output 'OS=windows'; Write-Output ('ARCH=' + $env:PROCESSOR_ARCHITECTURE); Write-Output ('HOME=' + $env:USERPROFILE); Write-Output ('PROGRAMS=' + $env:ProgramFiles)"),
                    Duration.ofSeconds(15)).thenApply(JavaRemotePlatform::parse);
        });
    }

    private String installedDirectory(JavaRemotePlatform platform) {
        return platform.windows() ? platform.programDirectory().replace('\\', '/') + "/JLShell/LinkAgent"
                : platform.homeDirectory() + "/.local/lib/jlshell-link-agent";
    }

    private CompletableFuture<Void> checkInstalled(JavaRemotePlatform platform, SignedAgentPackage artifact, List<String> trust) {
        String directory = installedDirectory(platform);
        String check = platform.windows() ? ps("if (Test-Path -LiteralPath " + pq(directory + "/link-agent.jar") + ") { exit 0 } else { exit 1 }")
                : "test -f " + q(directory + "/link-agent.jar");
        return ssh.commandExecutor().execute(check, Duration.ofSeconds(15)).thenCompose(output -> {
            if (output.exitCode() == 1) return CompletableFuture.completedFuture(null);
            if (output.exitCode() != 0) return CompletableFuture.failedFuture(new IllegalStateException("无法读取现有 Agent 状态"));
            return ssh.fileExplorer().readFile(directory + "/release.manifest.json")
                    .thenCombine(ssh.fileExplorer().readFile(directory + "/release.signature.json"), (manifest, signature) -> {
                        try {
                            var installed = SignedAgentPackage.verifyManifest(manifest, signature, trust);
                            if (SignedAgentPackage.compareVersions(artifact.version(), SignedAgentPackage.text(installed, "version")) < 0) {
                                throw new SecurityException("拒绝安装低于当前版本的 Agent；失败恢复由安装事务处理");
                            }
                            if (artifact.version().equals(SignedAgentPackage.text(installed, "version"))
                                    && !Arrays.equals(manifest, artifact.manifest())) {
                                throw new SecurityException("同版本 Agent 的签名清单发生变化");
                            }
                            return (Void) null;
                        } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                    });
        });
    }

    private CompletableFuture<String> deploy(JavaRemotePlatform platform, SignedAgentPackage artifact, Request request) {
        String stage = platform.homeDirectory().replace('\\', '/') + "/.jlshell-link-install-" + UUID.randomUUID();
        String zip = stage + "/package.zip";
        String root = stage + "/" + artifact.baseName();
        String create = platform.windows() ? ps("$ErrorActionPreference='Stop'; New-Item -ItemType Directory -Path " + pq(stage)
                + " | Out-Null; & icacls.exe " + pq(stage) + " /inheritance:r /grant:r (([Security.Principal.WindowsIdentity]::GetCurrent().Name)+':(OI)(CI)F') 'SYSTEM:(OI)(CI)F' | Out-Null; if ($LASTEXITCODE -ne 0) { exit 1 }")
                : "umask 077; mkdir -m 700 " + q(stage);
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<String> result = command(create, "无法建立隔离安装目录", 20)
                .thenRun(() -> created.set(true))
                .thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
                    try {
                        byte[] bytes;
                        try (var input = Files.newInputStream(artifact.archive())) {
                            bytes = input.readNBytes((int) SignedAgentPackage.MAX_ARCHIVE_BYTES + 1);
                        }
                        if (bytes.length > SignedAgentPackage.MAX_ARCHIVE_BYTES) throw new SecurityException("Agent 包在上传前变大");
                        if (!artifact.sha256().equals(SignedAgentPackage.digest(bytes))) throw new SecurityException("Agent 包在上传前被修改");
                        return bytes;
                    } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                })).thenCompose(bytes -> ssh.fileExplorer().writeFile(zip, bytes))
                .thenCompose(ignored -> ssh.fileExplorer().writeFile(stage + "/release.manifest.json", artifact.manifest()))
                .thenCompose(ignored -> ssh.fileExplorer().writeFile(stage + "/release.signature.json", artifact.signatureEnvelope()))
                .thenCompose(ignored -> command(extractCommand(platform, zip, stage, artifact.sha256()), "远端归档摘要检查或解包失败", 120))
                .thenCompose(ignored -> enroll(platform, root, stage, request))
                .thenCompose(ignored -> command(installCommand(platform, root, stage, request), "Agent 安装未完成；请检查服务恢复状态", 180))
                .thenApply(ignored -> "Java Agent 服务已安装，发布签名与远端归档摘要均通过；请核对 Website 在线状态。");
        String clean = platform.windows() ? ps("Remove-Item -LiteralPath " + pq(stage) + " -Recurse -Force -ErrorAction SilentlyContinue")
                : "rm -rf -- " + q(stage);
        return result.handle((value, error) -> (created.get()
                ? ssh.commandExecutor().execute(clean, Duration.ofSeconds(20))
                : CompletableFuture.completedFuture(new CommandOutput("", "", 0)))
                .handle((output, cleanupError) -> {
                    if (error != null) throw new java.util.concurrent.CompletionException(error);
                    if (cleanupError != null || output.exitCode() != 0) throw new IllegalStateException("服务已安装，但临时安装目录清理失败");
                    return value;
                })).thenCompose(future -> future);
    }

    private CompletableFuture<Void> enroll(JavaRemotePlatform platform, String root, String stage, Request request) {
        if (request.enrollmentToken().isEmpty()) return CompletableFuture.completedFuture(null);
        String tokenFile = stage + "/enrollment.token";
        byte[] token = request.enrollmentToken().getBytes(StandardCharsets.UTF_8);
        String state = platform.windows() ? platform.homeDirectory().replace('\\', '/') + "/.jlshell-link-agent"
                : platform.homeDirectory() + "/.local/state/jlshell-link-agent";
        String java = root + (platform.windows() ? "/runtime/bin/java.exe" : "/runtime/bin/java");
        String init = platform.windows() ? ps("$ErrorActionPreference='Stop'; & " + pq(java) + " -jar " + pq(root + "/link-agent.jar")
                + " init --state-dir " + pq(state) + " --tls-identity-p12 " + pq(request.identityPath())
                + " --tls-password-file " + pq(request.passwordPath()) + "; if ($LASTEXITCODE -ne 0) { exit 1 }; & " + pq(java)
                + " -jar " + pq(root + "/link-agent.jar") + " enroll --state-dir " + pq(state) + " --website " + pq(request.website())
                + " --agent-id " + pq(request.agentId()) + " --token-file " + pq(tokenFile) + "; exit $LASTEXITCODE")
                : "chmod 600 " + q(tokenFile) + "; " + q(java) + " -jar " + q(root + "/link-agent.jar") + " init --state-dir " + q(state)
                + " --tls-identity-p12 " + q(request.identityPath()) + " --tls-password-file " + q(request.passwordPath())
                + " && " + q(java) + " -jar " + q(root + "/link-agent.jar") + " enroll --state-dir " + q(state)
                + " --website " + q(request.website()) + " --agent-id " + q(request.agentId()) + " --token-file " + q(tokenFile);
        return ssh.fileExplorer().writeFile(tokenFile, token).whenComplete((ignored, error) -> Arrays.fill(token, (byte) 0))
                .thenCompose(ignored -> command(init, "Agent 初始化或注册失败；一次性令牌未写入日志", 90))
                .handle((ignored, error) -> ssh.fileExplorer().deleteFile(tokenFile).handle((cleaned, cleanupError) -> {
                    if (error != null) throw new java.util.concurrent.CompletionException(error);
                    if (cleanupError != null) throw new IllegalStateException("注册完成但令牌临时文件清理失败");
                    return (Void) null;
                })).thenCompose(future -> future);
    }

    static String extractCommand(JavaRemotePlatform platform, String zip, String stage, String expected) {
        return platform.windows() ? ps("$ErrorActionPreference='Stop'; if ((Get-FileHash -Algorithm SHA256 -LiteralPath " + pq(zip)
                + ").Hash.ToLowerInvariant() -ne " + pq(expected) + ") { exit 1 }; Expand-Archive -LiteralPath " + pq(zip) + " -DestinationPath " + pq(stage))
                : "set -eu; chmod 600 " + q(zip) + "; if command -v sha256sum >/dev/null; then actual=$(sha256sum " + q(zip)
                + " | cut -d ' ' -f 1); else actual=$(shasum -a 256 " + q(zip) + " | cut -d ' ' -f 1); fi; [ \"$actual\" = "
                + q(expected) + " ]; unzip -q " + q(zip) + " -d " + q(stage);
    }

    private static String installCommand(JavaRemotePlatform platform, String root, String stage, Request request) {
        return platform.windows() ? ps("$ErrorActionPreference='Stop'; $env:JLSHELL_LINK_STUN_SERVERS=" + pq(request.stunServers()) + "; & " + pq(root + "/scripts/java-agent/upgrade-windows-service.ps1")
                + " -StateDirectory " + pq(platform.homeDirectory().replace('\\', '/') + "/.jlshell-link-agent")
                + " -LinkWssUri " + pq(request.wss()) + " -TlsIdentityP12 " + pq(request.identityPath())
                + " -TlsPasswordFile " + pq(request.passwordPath()) + " -AllowedTargetsFile " + pq(request.targetsPath())
                + " -Manifest " + pq(stage + "/release.manifest.json") + " -TicketIssuer " + pq(request.website()))
                : "JLSHELL_LINK_STUN_SERVERS=" + q(request.stunServers()) + " sh " + q(root + "/scripts/java-agent/upgrade-user-service.sh") + " " + q(request.wss()) + " "
                + q(request.identityPath()) + " " + q(request.passwordPath()) + " " + q(request.targetsPath()) + " "
                + q(stage + "/release.manifest.json") + " " + q(request.website());
    }

    private CompletableFuture<Void> command(String command, String message, int seconds) {
        return ssh.commandExecutor().execute(command, Duration.ofSeconds(seconds)).thenApply(output -> {
            // Remote stderr/stdout may contain paths or node metadata; do not echo either into UI/logs.
            if (output.exitCode() != 0) throw new IllegalStateException(message);
            return null;
        });
    }

    static void validateZip(SignedAgentPackage artifact) throws Exception {
        long total = 0; int count = 0;
        var names = new java.util.HashSet<String>();
        try (ZipFile zip = new ZipFile(artifact.archive().toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String path = entry.getName();
                if (!path.startsWith(artifact.baseName() + "/") || path.contains("\\") || path.contains(":")
                        || path.chars().anyMatch(c -> c < 32) || Arrays.asList(path.split("/", -1)).contains("..") || Arrays.asList(path.split("/", -1)).contains(".")
                        || path.contains("//")
                        || !names.add(path) || ++count > 20_000 || entry.getSize() < 0
                        || (total += entry.getSize()) > 1024L * 1024 * 1024) {
                    throw new SecurityException("Agent ZIP 包含非法路径、重复文件或超大展开内容");
                }
            }
            if (!names.contains(artifact.baseName() + "/link-agent.jar")
                    || !names.contains(artifact.baseName() + "/runtime/bin/" + (artifact.platform().equals("windows") ? "java.exe" : "java"))) {
                throw new SecurityException("Agent 包缺少业务 JAR 或内置 Java runtime");
            }
        }
    }

    static String q(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static String pq(String value) { return "'" + value.replace("'", "''") + "'"; }
    static String ps(String script) {
        return "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "
                + java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }
    private static void requireHttps(String value) {
        var uri = java.net.URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Website 必须使用 HTTPS");
    }
}
