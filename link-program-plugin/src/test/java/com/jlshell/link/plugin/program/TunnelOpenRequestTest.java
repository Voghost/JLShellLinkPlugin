package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class TunnelOpenRequestTest {

    @Test
    void parsesExactIpTicketAndIpMultiaddrs() {
        JsonObject args = validArgs();
        args.addProperty("targetIp", "::1");

        TunnelOpenRequest request = TunnelOpenRequest.parse(args);

        assertThat(request.targetIp()).isEqualTo("::1");
        assertThat(request.targetPort()).isEqualTo(22);
        assertThat(request.ticket()).containsExactly(1, 2, 3);
        assertThat(request.agentAddresses()).containsExactly("/ip4/127.0.0.1/tcp/7001");
    }

    @Test
    void normalizesWebsiteAgentAndRelayPeerIdsForTheConnector() {
        JsonObject args = validArgs();
        args.addProperty("agentPeer", PeerIdCodecTest.WEBSITE_PEER);
        args.addProperty("relayAddress", "/ip4/127.0.0.1/tcp/7000");
        args.addProperty("relayPeer", PeerIdCodecTest.WEBSITE_PEER);

        TunnelOpenRequest request = TunnelOpenRequest.parse(args);

        assertThat(request.agentPeer()).isEqualTo(PeerIdCodecTest.LIBP2P_PEER);
        assertThat(request.relayPeer()).isEqualTo(PeerIdCodecTest.LIBP2P_PEER);
    }

    @Test
    void rejectsDnsTargetsAndIncompleteRelayConfiguration() {
        JsonObject dns = validArgs();
        dns.addProperty("targetIp", "ssh.internal.example");
        assertThatThrownBy(() -> TunnelOpenRequest.parse(dns))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact IP");

        JsonObject relay = validArgs();
        relay.addProperty("relayAddress", "/ip4/127.0.0.1/tcp/7000");
        assertThatThrownBy(() -> TunnelOpenRequest.parse(relay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplied together");
    }

    static JsonObject validArgs() {
        JsonObject args = new JsonObject();
        args.addProperty("agentPeer", PeerIdCodecTest.LIBP2P_PEER);
        var addresses = new com.google.gson.JsonArray();
        addresses.add("/ip4/127.0.0.1/tcp/7001");
        args.add("agentAddresses", addresses);
        args.addProperty("connectPolicy", "auto");
        args.addProperty("ticket", "AQID");
        args.addProperty("targetIp", "127.0.0.1");
        args.addProperty("targetPort", 22);
        return args;
    }
}
