package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "localhost", "0177.0.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "",
            "::zz", "1:2:3"})
    void aNameOrAMalformedAddressIsNotALiteral(String host) {
        assertThat(HostAddresses.literal(host)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "8.8.8.8", "::1", "[::1]", "fe80::1", "2001:db8::1"})
    void aLiteralIsParsedWithoutAskingDns(String host) {
        assertThat(HostAddresses.literal(host)).isPresent();
    }

    @Test
    void localhostAndLoopbackLiteralsAreLoopbackButNamesAreNot() {
        assertThat(HostAddresses.isLoopbackHost("localhost")).isTrue();
        assertThat(HostAddresses.isLoopbackHost("127.0.0.1")).isTrue();
        assertThat(HostAddresses.isLoopbackHost("[::1]")).isTrue();
        assertThat(HostAddresses.isLoopbackHost("10.0.0.1")).isFalse();
        assertThat(HostAddresses.isLoopbackHost("localhost.example.com")).isFalse();
        assertThat(HostAddresses.isLoopbackHost("example.com")).isFalse();
        assertThat(HostAddresses.isLoopbackHost(null)).isFalse();
    }

    @Test
    void theWildcardAddressesAreNeverPrivate() throws Exception {
        assertThat(HostAddresses.isPrivateOrLoopback(InetAddress.getByName("0.0.0.0"))).isFalse();
        assertThat(HostAddresses.isPrivateOrLoopback(InetAddress.getByName("::"))).isFalse();
        assertThat(HostAddresses.isPrivateOrLoopback(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(HostAddresses.isPrivateOrLoopback(InetAddress.getByName("100.128.0.1"))).isFalse();
        assertThat(HostAddresses.isPrivateOrLoopback(InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(HostAddresses.isPrivateOrLoopback(InetAddress.getByName("fc00::1"))).isTrue();
    }

    @Test
    void hostFormsListTheWaysAClientWritesTheAddress() throws Exception {
        assertThat(HostAddresses.hostForms(InetAddress.getByName("10.1.2.3"))).containsExactly("10.1.2.3");
        assertThat(HostAddresses.hostForms(InetAddress.getByName("::1")))
                .containsExactly("[0:0:0:0:0:0:0:1]", "[::1]");
        assertThat(HostAddresses.hostForms(InetAddress.getByName("fd00::1:2")))
                .containsExactly("[fd00:0:0:0:0:0:1:2]", "[fd00::1:2]");
    }

    @Test
    void ipv6CompressionUsesTheLongestRunOfZeros() throws Exception {
        assertThat(HostAddresses.compressIpv6(InetAddress.getByName("2001:db8:0:0:1:0:0:1").getAddress()))
                .isEqualTo("2001:db8::1:0:0:1");
        assertThat(HostAddresses.compressIpv6(InetAddress.getByName("1:0:2:3:4:5:6:7").getAddress()))
                .isEqualTo("1:0:2:3:4:5:6:7");
        assertThat(HostAddresses.compressIpv6(InetAddress.getByName("::").getAddress())).isEqualTo("::");
    }
}
