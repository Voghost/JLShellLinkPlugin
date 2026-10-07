package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import com.jlshell.link.plugin.program.session.SignedAgentPackage;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AgentPublisherTrustTest {
    @Test
    void officialAnchorIsAvailableWithoutManualConfiguration() throws Exception {
        String defaults = AgentPublisherTrust.configuredOrDefault("");
        assertThat(SignedAgentPackage.parseTrustedKeys(defaults)).hasSize(1);
        assertThat(AgentPublisherTrust.configuredOrDefault(null)).isEqualTo(defaults);
    }

    @Test
    void explicitSelfHostedPublisherReplacesOfficialDefaults() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        String configured = Base64.getEncoder().encodeToString(key.getEncoded());
        assertThat(AgentPublisherTrust.configuredOrDefault(configured)).isEqualTo(configured);
        assertThat(SignedAgentPackage.parseTrustedKeys(configured)).containsExactly(configured);
    }
}
