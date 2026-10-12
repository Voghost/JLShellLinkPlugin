package com.jlshell.link.plugin.program;

import java.nio.charset.StandardCharsets;

/** Official publisher anchor ships independently of downloadable Agent manifests. */
final class AgentPublisherTrust {
    private AgentPublisherTrust() { }

    static String configuredOrDefault(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        try (var stream = AgentPublisherTrust.class.getResourceAsStream(
                "/META-INF/jlshell-link/publisher-public-keys.txt")) {
            if (stream == null) return "";
            String value = new String(stream.readAllBytes(), StandardCharsets.US_ASCII).trim();
            var keys = com.jlshell.link.plugin.program.session.SignedAgentPackage.parseTrustedKeys(value);
            return String.join(",", keys);
        } catch (Exception invalid) {
            throw new IllegalStateException("官方 Agent 发布公钥无效", invalid);
        }
    }
}
