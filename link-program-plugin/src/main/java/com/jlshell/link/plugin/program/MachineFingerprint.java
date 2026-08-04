package com.jlshell.link.plugin.program;

import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只在本机读取平台标识，并返回产品域 SHA-256；原始标识不得离开当前进程。 */
final class MachineFingerprint {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern MAC_UUID = Pattern.compile("\\\"IOPlatformUUID\\\"\\s*=\\s*\\\"([^\\\"]+)\\\"");

    private MachineFingerprint() { }

    static String current() {
        String source = platformIdentifier();
        if (source == null || source.isBlank()) {
            source = networkIdentifier();
        }
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("无法读取本机稳定标识，当前设备不能领取试用；可联系管理员手工授权套餐");
        }
        return hash(source);
    }

    static String hash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = source.trim().toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(digest.digest(
                    ("jlshell-trial-machine-v1\n" + normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成试用机器指纹", error);
        }
    }

    private static String platformIdentifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            for (Path path : List.of(Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id"))) {
                try {
                    if (Files.isRegularFile(path)) {
                        String value = Files.readString(path, StandardCharsets.UTF_8).trim();
                        if (!value.isBlank()) return "linux:" + value;
                    }
                } catch (IOException ignored) { }
            }
        } else if (os.contains("mac")) {
            String output = command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
            Matcher matcher = MAC_UUID.matcher(output == null ? "" : output);
            if (matcher.find()) return "macos:" + matcher.group(1);
        } else if (os.contains("win")) {
            String output = command("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                    "/v", "MachineGuid");
            if (output != null) {
                for (String line : output.lines().toList()) {
                    if (line.toLowerCase(Locale.ROOT).contains("machineguid")) {
                        String[] fields = line.trim().split("\\s+");
                        if (fields.length >= 3) return "windows:" + fields[fields.length - 1];
                    }
                }
            }
        }
        return null;
    }

    private static String networkIdentifier() {
        try {
            List<String> addresses = new ArrayList<>();
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface value = interfaces.nextElement();
                byte[] address = value.getHardwareAddress();
                if (address != null && address.length >= 6 && !value.isLoopback()) {
                    addresses.add(HexFormat.of().formatHex(address));
                }
            }
            addresses.sort(Comparator.naturalOrder());
            return addresses.isEmpty() ? null : "network:" + String.join(",", addresses);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String command(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] output = process.getInputStream().readNBytes(64 * 1024);
            return process.exitValue() == 0 ? new String(output, StandardCharsets.UTF_8) : null;
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }
}
