package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PeerIdCodecTest {
    static final String WEBSITE_PEER = "ACQIARIg42DKI3ZlWh8mjGrxyNJV2x0cKpetIcALGt5s-aIV7Eo";
    static final String LIBP2P_PEER = "12D3KooWR7xKN3mTKfPBymKX6Apaepsji97Xutr54rCgCd5QLgBb";

    @Test
    void convertsWebsiteBase64UrlMultihashToLibp2pBase58() {
        assertThat(PeerIdCodec.toBase58(WEBSITE_PEER)).isEqualTo(LIBP2P_PEER);
    }

    @Test
    void preservesCanonicalLibp2pBase58PeerId() {
        assertThat(PeerIdCodec.toBase58(LIBP2P_PEER)).isEqualTo(LIBP2P_PEER);
    }

    @Test
    void rejectsStringsThatAreNotSupportedPeerIdMultihashes() {
        assertThatThrownBy(() -> PeerIdCodec.toBase58("connector-peer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PeerId");
        assertThatThrownBy(() -> PeerIdCodec.toBase58("AQID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PeerId");
    }
}
