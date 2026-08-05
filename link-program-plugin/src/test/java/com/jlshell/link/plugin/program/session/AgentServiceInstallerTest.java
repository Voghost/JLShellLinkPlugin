package com.jlshell.link.plugin.program.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.jlshell.plugin.api.model.CommandOutput;
import org.junit.jupiter.api.Test;

class AgentServiceInstallerTest {
    @Test
    void buildsManagedAgentArgumentsAndNumericAdvertisements() {
        RemotePlatform linux = RemotePlatform.parse(new CommandOutput(
                "OS=Linux\nARCH=x86_64\nHOME=/home/test\n", "", 0));

        List<String> arguments = AgentServiceInstaller.arguments(
                linux, "https://jlshell.example.com", "203.0.113.10",
                "/ip4/198.51.100.20/tcp/4001", "12D3KooWRelay");

        assertThat(arguments).containsSubsequence("--credential-file",
                "/home/test/.jlshell-link/bin/agent-token");
        assertThat(arguments).doesNotContain("--allow-target");
        assertThat(arguments).containsSubsequence("--advertise", "/ip4/203.0.113.10/tcp/7001");
        assertThat(arguments).containsSubsequence("--advertise", "/ip4/203.0.113.10/udp/7001/quic-v1");
        assertThat(arguments).containsSubsequence("--relay-address", "/ip4/198.51.100.20/tcp/4001",
                "--relay-peer", "12D3KooWRelay");
        assertThat(AgentServiceInstaller.advertisedAddresses("agent.example.com")).isEmpty();
        assertThat(AgentServiceInstaller.advertisedAddresses("0.0.0.0")).isEmpty();
        assertThat(AgentServiceInstaller.windowsQuote("C:\\Users\\test\\jlshell-agent.exe"))
                .isEqualTo("\"C:\\Users\\test\\jlshell-agent.exe\"");
    }
}
