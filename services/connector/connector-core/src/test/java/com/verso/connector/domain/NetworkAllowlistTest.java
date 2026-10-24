package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/** The connector's egress allowlist (ADR-0038, security-rules 5 SSRF). */
class NetworkAllowlistTest {

    @Test
    void parse_whenAnEntryIsAPublicRange_refusesToStart() {
        for (String publicRange : new String[]{"0.0.0.0/0", "8.8.8.0/24", "10.0.0.0/7", "172.32.0.0/16", "2001:db8::/32",
                "::/0"}) {
            assertThatThrownBy(() -> NetworkAllowlist.parse(publicRange)).as(publicRange)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private ranges");
        }
        for (String malformed : new String[]{"10.0.0.0", "10.0.0.0/33", "fileserver/24", "10.0.0.0/x"}) {
            assertThatThrownBy(() -> NetworkAllowlist.parse(malformed)).as(malformed)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allows_whenTheAddressIsInsideAnAllowedBlock_isTrue_andOutsideFalse() throws Exception {
        NetworkAllowlist list = NetworkAllowlist.parse("10.20.0.0/16, 192.168.5.10/32,fd00:1::/64");

        assertThat(list.size()).isEqualTo(3);
        assertThat(list.allows(ip("10.20.30.40"))).isTrue();
        assertThat(list.allows(ip("10.21.0.1"))).isFalse();
        assertThat(list.allows(ip("192.168.5.10"))).isTrue();
        assertThat(list.allows(ip("192.168.5.11"))).isFalse();
        assertThat(list.allows(ip("fd00:1::5"))).isTrue();
        assertThat(list.allows(ip("8.8.8.8"))).isFalse();
    }

    @Test
    void allows_whenAnIpv4AddressIsSpelledAsIpv6_judgesTheIpv4Address() throws Exception {
        NetworkAllowlist list = NetworkAllowlist.parse("10.0.0.0/8");

        assertThat(list.allows(ip("::ffff:10.1.2.3"))).isTrue();
        assertThat(list.allows(ip("::ffff:127.0.0.1"))).isFalse();
        assertThat(list.allows(ip("::ffff:8.8.8.8"))).isFalse();
    }

    @Test
    void allows_whenLoopbackLinkLocalOrWildcard_isAlwaysFalse() throws Exception {
        NetworkAllowlist list = NetworkAllowlist.parse("10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7");

        for (String never : new String[]{"127.0.0.1", "169.254.169.254", "0.0.0.0", "::1", "fe80::1", "::",
                "255.255.255.255", "224.0.0.1"}) {
            assertThat(list.allows(ip(never))).as(never).isFalse();
        }
    }

    @Test
    void parse_whenEmpty_allowsNothing() throws Exception {
        assertThat(NetworkAllowlist.parse("").isEmpty()).isTrue();
        assertThat(NetworkAllowlist.parse(null).allows(ip("10.0.0.1"))).isFalse();
        assertThat(NetworkAllowlist.parse(" , ").isEmpty()).isTrue();
    }

    @Test
    void isLiteral_whenAName_isFalse_soItIsResolvedAndCheckedAtSyncTime() {
        assertThat(NetworkAllowlist.isLiteral("10.0.0.1")).isTrue();
        assertThat(NetworkAllowlist.isLiteral("fd00::1")).isTrue();
        assertThat(NetworkAllowlist.isLiteral("fs01.corp.local")).isFalse();
        assertThat(NetworkAllowlist.isLiteral("10.1")).as("no shortened IPv4 forms").isFalse();
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }
}
