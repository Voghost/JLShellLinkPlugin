package com.jlshell.link.plugin.session;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.jlshell.plugin.api.model.CommandOutput;

record RemotePlatform(String platform, String architecture, String homeDirectory) {

    static RemotePlatform parse(CommandOutput output) {
        if (output == null || output.exitCode() != 0) {
            throw new IllegalArgumentException("Remote platform detection failed");
        }
        Map<String, String> values = new LinkedHashMap<>();
        output.stdout().lines().forEach(line -> {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        });
        String os = required(values, "OS").toLowerCase(Locale.ROOT);
        String arch = required(values, "ARCH").toLowerCase(Locale.ROOT);
        String platform = switch (os) {
            case "linux" -> "linux";
            case "darwin", "macos", "macosx" -> "macos";
            case "windows", "win32_nt" -> "windows";
            default -> throw new IllegalArgumentException("Unsupported remote OS: " + os);
        };
        String architecture = switch (arch) {
            case "x86_64", "amd64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IllegalArgumentException("Unsupported remote architecture: " + arch);
        };
        String home = required(values, "HOME");
        validateHome(platform, home);
        return new RemotePlatform(platform, architecture, home);
    }

    boolean windows() {
        return "windows".equals(platform);
    }

    String remoteDirectory() {
        return windows()
                ? homeDirectory.replace('\\', '/') + "/.jlshell-link/bin"
                : homeDirectory + "/.jlshell-link/bin";
    }

    String remoteBinary() {
        return remoteDirectory() + (windows() ? "/jlshell-agent.exe" : "/jlshell-agent");
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Remote platform response omitted " + name);
        }
        return value;
    }

    private static void validateHome(String platform, String home) {
        if (home.length() > 1000 || home.indexOf('\0') >= 0 || home.contains("\n") || home.contains("\r")) {
            throw new IllegalArgumentException("Remote home directory is invalid");
        }
        if ("windows".equals(platform)) {
            if (!home.matches("^[A-Za-z]:[\\\\/][A-Za-z0-9 ._()\\\\/-]+$")) {
                throw new IllegalArgumentException("Remote Windows home directory is invalid");
            }
        } else if (!home.startsWith("/")) {
            throw new IllegalArgumentException("Remote Unix home directory is invalid");
        }
    }
}
