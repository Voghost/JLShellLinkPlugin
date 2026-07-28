package com.jlshell.link.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jlshell.plugin.api.model.CommandOutput;
import org.junit.jupiter.api.Test;

class RemotePlatformTest {

    @Test
    void normalizesSupportedUnixAndWindowsPlatforms() {
        RemotePlatform linux = RemotePlatform.parse(new CommandOutput(
                "OS=Linux\nARCH=x86_64\nHOME=/home/jlshell\n", "", 0));
        assertThat(linux.platform()).isEqualTo("linux");
        assertThat(linux.architecture()).isEqualTo("x64");
        assertThat(linux.remoteBinary()).isEqualTo("/home/jlshell/.jlshell-link/bin/jlshell-agent");

        RemotePlatform windows = RemotePlatform.parse(new CommandOutput(
                "OS=Windows\nARCH=AMD64\nHOME=C:\\Users\\jlshell\n", "", 0));
        assertThat(windows.platform()).isEqualTo("windows");
        assertThat(windows.remoteBinary()).isEqualTo("C:/Users/jlshell/.jlshell-link/bin/jlshell-agent.exe");
    }

    @Test
    void rejectsRelativeHomeAndUnsupportedArchitecture() {
        assertThatThrownBy(() -> RemotePlatform.parse(new CommandOutput(
                "OS=Linux\nARCH=x86_64\nHOME=relative\n", "", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemotePlatform.parse(new CommandOutput(
                "OS=Linux\nARCH=riscv64\nHOME=/home/test\n", "", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("architecture");
    }
}
