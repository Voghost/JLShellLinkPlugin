package com.jlshell.link.plugin.program.session;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.model.CommandOutput;

final class AgentServiceInstaller {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SERVICE_NAME = "JLShellLinkAgent";
    private final SshSessionContext ssh;

    AgentServiceInstaller(SshSessionContext ssh) {
        this.ssh = ssh;
    }

    CompletableFuture<String> install(RemotePlatform platform, String executable, String baseUrl,
                                      String relayAddress, String relayPeer) {
        List<String> arguments = arguments(platform, baseUrl, ssh.host(), relayAddress, relayPeer);
        return switch (platform.platform()) {
            case "linux" -> installSystemd(platform, executable, arguments);
            case "macos" -> installLaunchAgent(platform, executable, arguments);
            case "windows" -> installWindowsService(platform, executable, arguments);
            default -> CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported service platform: " + platform.platform()));
        };
    }

    static List<String> arguments(RemotePlatform platform, String baseUrl, String sshHost,
                                  String relayAddress, String relayPeer) {
        String directory = platform.remoteDirectory();
        List<String> result = new ArrayList<>(List.of(
                "--identity", directory + "/agent-identity.key",
                "--authority-public", directory + "/authority.json",
                "--listen", "/ip4/0.0.0.0/tcp/7001",
                "--listen", "/ip4/0.0.0.0/udp/7001/quic-v1",
                "--control-plane-url", baseUrl,
                "--credential-file", directory + "/agent-token"));
        advertisedAddresses(sshHost).forEach(address -> {
            result.add("--advertise");
            result.add(address);
        });
        if (relayAddress != null && !relayAddress.isBlank()
                && relayPeer != null && !relayPeer.isBlank()) {
            result.add("--relay-address");
            result.add(relayAddress);
            result.add("--relay-peer");
            result.add(relayPeer);
        }
        return List.copyOf(result);
    }

    static List<String> advertisedAddresses(String host) {
        try {
            String value = host == null ? "" : host.trim();
            if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
            boolean possibleV4 = value.matches("[0-9.]+");
            boolean possibleV6 = value.contains(":") && !value.contains("%");
            if (!possibleV4 && !possibleV6) return List.of();
            InetAddress address = InetAddress.getByName(value);
            if (address.isAnyLocalAddress() || address.isMulticastAddress()) return List.of();
            String prefix;
            if (possibleV4 && address instanceof Inet4Address) prefix = "/ip4/" + address.getHostAddress();
            else if (possibleV6 && address instanceof Inet6Address) prefix = "/ip6/" + address.getHostAddress();
            else return List.of();
            return List.of(prefix + "/tcp/7001", prefix + "/udp/7001/quic-v1");
        } catch (RuntimeException | java.net.UnknownHostException error) {
            return List.of();
        }
    }

    private CompletableFuture<String> installSystemd(
            RemotePlatform platform, String executable, List<String> arguments) {
        String unitDirectory = platform.homeDirectory() + "/.config/systemd/user";
        String unitPath = unitDirectory + "/jlshell-link-agent.service";
        String exec = systemdQuote(executable) + " " + arguments.stream()
                .map(AgentServiceInstaller::systemdQuote)
                .collect(java.util.stream.Collectors.joining(" "));
        String unit = """
                [Unit]
                Description=JLShell Link Agent
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                ExecStart=%s
                Restart=always
                RestartSec=5
                UMask=0077
                NoNewPrivileges=true
                PrivateTmp=true

                [Install]
                WantedBy=default.target
                """.formatted(exec);
        return requireSuccess(ssh.commandExecutor().execute(
                        "mkdir -p " + shellQuote(unitDirectory), TIMEOUT), "Cannot create systemd user directory")
                .thenCompose(ignored -> writeFile(platform, unitPath, unit.getBytes(StandardCharsets.UTF_8)))
                .thenCompose(ignored -> requireSuccess(ssh.commandExecutor().execute(
                        "systemctl --user daemon-reload"
                                + " && systemctl --user enable jlshell-link-agent.service"
                                + " && systemctl --user restart jlshell-link-agent.service"
                                + " && systemctl --user is-active --quiet jlshell-link-agent.service",
                        TIMEOUT), "Cannot enable systemd user service"))
                .thenApply(ignored -> "SYSTEMD_USER");
    }

    private CompletableFuture<String> installLaunchAgent(
            RemotePlatform platform, String executable, List<String> arguments) {
        String launchDirectory = platform.homeDirectory() + "/Library/LaunchAgents";
        String plistPath = launchDirectory + "/com.jlshell.link.agent.plist";
        StringBuilder programArguments = new StringBuilder()
                .append("    <string>").append(xml(executable)).append("</string>\n");
        arguments.forEach(argument -> programArguments.append("    <string>")
                .append(xml(argument)).append("</string>\n"));
        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
                  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>com.jlshell.link.agent</string>
                  <key>ProgramArguments</key>
                  <array>
                %s  </array>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><true/>
                  <key>ProcessType</key><string>Background</string>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                </dict>
                </plist>
                """.formatted(programArguments, xml(platform.remoteDirectory() + "/agent.log"),
                xml(platform.remoteDirectory() + "/agent-error.log"));
        String domain = "gui/$(id -u)";
        String install = "launchctl bootout " + domain + " " + shellQuote(plistPath)
                + " >/dev/null 2>&1 || true; launchctl bootstrap " + domain + " "
                + shellQuote(plistPath) + " && launchctl enable "
                + domain + "/com.jlshell.link.agent && launchctl print "
                + domain + "/com.jlshell.link.agent >/dev/null";
        return requireSuccess(ssh.commandExecutor().execute(
                        "mkdir -p " + shellQuote(launchDirectory), TIMEOUT),
                        "Cannot create LaunchAgents directory")
                .thenCompose(ignored -> writeFile(platform, plistPath, plist.getBytes(StandardCharsets.UTF_8)))
                .thenCompose(ignored -> requireSuccess(ssh.commandExecutor().execute(
                        install, TIMEOUT), "Cannot bootstrap macOS LaunchAgent"))
                .thenApply(ignored -> "LAUNCH_AGENT");
    }

    private CompletableFuture<String> installWindowsService(
            RemotePlatform platform, String executable, List<String> arguments) {
        String scriptPath = platform.remoteDirectory() + "/install-service.ps1";
        String aclDirectory = platform.homeDirectory().replace('\\', '/') + "/.jlshell-link";
        String commandLine = windowsQuote(executable) + " --windows-service "
                + arguments.stream().map(AgentServiceInstaller::windowsQuote)
                .collect(java.util.stream.Collectors.joining(" "));
        String script = """
                $ErrorActionPreference = 'Stop'
                $name = '%s'
                $binaryPath = '%s'
                $aclDirectory = '%s'
                $serviceAccount = 'NT SERVICE\\' + $name
                $operator = [Security.Principal.WindowsIdentity]::GetCurrent().Name
                $existing = Get-Service -Name $name -ErrorAction SilentlyContinue
                if ($null -ne $existing) {
                  if ($existing.Status -ne 'Stopped') { Stop-Service -Name $name -Force }
                  & sc.exe delete $name | Out-Null
                  if ($LASTEXITCODE -ne 0) { throw 'Cannot delete existing JLShell Link Agent service' }
                  Start-Sleep -Seconds 1
                }
                & sc.exe create $name binPath= $binaryPath start= auto obj= $serviceAccount DisplayName= 'JLShell Link Agent' | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Cannot create JLShell Link Agent service; elevated rights are required' }
                & sc.exe sidtype $name unrestricted | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Cannot enable JLShell Link Agent service SID' }
                & icacls.exe $aclDirectory /inheritance:r /grant:r "${operator}:(OI)(CI)F" "SYSTEM:(OI)(CI)F" "${serviceAccount}:(OI)(CI)RX" | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Cannot protect JLShell Link Agent runtime files' }
                & sc.exe failure $name reset= 86400 actions= restart/5000/restart/5000/restart/5000 | Out-Null
                & sc.exe failureflag $name 1 | Out-Null
                Start-Service -Name $name
                (Get-Service -Name $name).WaitForStatus('Running', [TimeSpan]::FromSeconds(10))
                """.formatted(SERVICE_NAME, powershellLiteral(commandLine),
                powershellLiteral(aclDirectory));
        return writeFile(platform, scriptPath, script.getBytes(StandardCharsets.UTF_8))
                .thenCompose(ignored -> requireSuccess(ssh.commandExecutor().execute(
                        "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File '"
                                + powershellLiteral(scriptPath) + "'", TIMEOUT),
                        "Cannot install Windows service"))
                .thenApply(ignored -> "WINDOWS_SERVICE");
    }

    private CompletableFuture<Void> writeFile(RemotePlatform platform, String destination, byte[] content) {
        String temporary = destination + ".tmp-" + java.util.UUID.randomUUID();
        CompletableFuture<Void> result = ssh.fileExplorer().writeFile(temporary, content);
        if (!platform.windows()) {
            result = result.thenCompose(ignored -> requireSuccess(ssh.commandExecutor().execute(
                    "chmod 600 " + shellQuote(temporary), TIMEOUT), "Cannot protect service definition"));
        }
        String promote = platform.windows()
                ? "powershell -NoProfile -NonInteractive -Command \"Move-Item -Force -LiteralPath '"
                    + powershellLiteral(temporary) + "' -Destination '" + powershellLiteral(destination) + "'\""
                : "mv -f " + shellQuote(temporary) + " " + shellQuote(destination);
        return result.thenCompose(ignored -> requireSuccess(
                        ssh.commandExecutor().execute(promote, TIMEOUT), "Cannot activate service definition"))
                .exceptionallyCompose(error -> ssh.fileExplorer().deleteFile(temporary)
                        .handle((ignored, cleanupError) -> (Void) null)
                        .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
    }

    private static CompletableFuture<Void> requireSuccess(
            CompletableFuture<CommandOutput> future, String message) {
        return future.thenApply(output -> {
            if (output.exitCode() != 0) {
                throw new IllegalStateException(message + ": " + output.stderr().trim());
            }
            return null;
        });
    }

    private static String systemdQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\"";
    }

    static String windowsQuote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String powershellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
