package com.jlshell.link.plugin.program;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MachineFingerprintTest {
    @Test
    void hashesPlatformIdentifierInProductDomainWithoutExposingSource() {
        String first = MachineFingerprint.hash("macos:EXAMPLE-MACHINE-ID");
        String second = MachineFingerprint.hash("  MACOS:example-machine-id  ");

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        assertThat(first).doesNotContain("example-machine-id");
        assertThat(first).isNotEqualTo(MachineFingerprint.hash("macos:another-machine"));
    }
}
