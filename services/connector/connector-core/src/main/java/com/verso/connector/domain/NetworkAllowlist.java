package com.verso.connector.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where a connector may connect (ADR-0038, security-rules 5 SSRF): the installation lists its internal networks as
 * CIDR blocks, and an address is allowed only inside one of them. The list itself may only name private ranges
 * (RFC 1918, RFC 4193 unique-local): SMB is an internal-network protocol, and a typo must not open the internet in local
 * mode (ADR-0006). Loopback, link-local (cloud metadata 169.254.169.254), wildcard, multicast and broadcast addresses
 * are refused even inside an allowed block. An empty list allows nothing: a fresh installation cannot reach any host.
 */
public final class NetworkAllowlist {

    /** Dotted quad or an IPv6 form (a colon never reaches DNS); shortened IPv4 forms such as "10.1" are refused. */
    private static final Pattern LITERAL = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    private static final List<Block> PRIVATE = List.of(
            Block.parse("10.0.0.0/8"), Block.parse("172.16.0.0/12"), Block.parse("192.168.0.0/16"),
            Block.parse("fc00::/7"));

    private final List<Block> blocks;

    private NetworkAllowlist(List<Block> blocks) {
        this.blocks = blocks;
    }

    /**
     * Parses a comma-separated list such as {@code 10.20.0.0/16,192.168.5.10/32}.
     *
     * @throws IllegalArgumentException when an entry is not a CIDR block inside a private range
     */
    public static NetworkAllowlist parse(String csv) {
        List<Block> blocks = new ArrayList<>();
        if (csv != null) {
            for (String entry : csv.split(",")) {
                if (entry.isBlank()) continue;
                Block block = Block.parse(entry.strip());
                if (PRIVATE.stream().noneMatch(p -> p.contains(block))) {
                    throw new IllegalArgumentException("verso.connector.allowed-networks may only name private ranges "
                            + "(10/8, 172.16/12, 192.168/16, fc00::/7): " + entry.strip());
                }
                blocks.add(block);
            }
        }
        return new NetworkAllowlist(List.copyOf(blocks));
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    /** Whether a connection to this resolved address is allowed. */
    public boolean allows(InetAddress address) {
        InetAddress normalized = unmapped(address);
        if (isForbidden(normalized)) return false;
        return blocks.stream().anyMatch(b -> b.contains(normalized));
    }

    /** Whether the text is an IP literal (then no DNS lookup is needed to judge it). */
    public static boolean isLiteral(String host) {
        return host != null && LITERAL.matcher(host).matches();
    }

    /** Parses an IP literal without any name lookup. */
    public static InetAddress literal(String host) {
        if (!isLiteral(host)) throw new IllegalArgumentException("not an IP literal");
        try {
            // A literal never triggers a lookup; the pattern above keeps names away from this call.
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IP literal");
        }
    }

    /** Never a target, whatever the list says. */
    static boolean isForbidden(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return (b[0] & 0xff) == 0 || ((b[0] & 0xff) == 255 && (b[1] & 0xff) == 255 && (b[2] & 0xff) == 255
                    && (b[3] & 0xff) == 255);
        }
        return false;
    }

    /** ::ffff:10.0.0.1 is 10.0.0.1: the allowlist must not be bypassed by spelling an IPv4 address as IPv6. */
    static InetAddress unmapped(InetAddress a) {
        if (!(a instanceof Inet6Address)) return a;
        byte[] b = a.getAddress();
        for (int i = 0; i < 10; i++) if (b[i] != 0) return a;
        if ((b[10] & 0xff) != 0xff || (b[11] & 0xff) != 0xff) return a;
        try {
            return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
            return a;
        }
    }

    /** One CIDR block. */
    record Block(byte[] network, int prefix) {

        static Block parse(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash < 0) throw new IllegalArgumentException("not a CIDR block: " + cidr);
            String host = cidr.substring(0, slash);
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a CIDR block: " + cidr);
            }
            if (!isLiteral(host)) throw new IllegalArgumentException("not a CIDR block: " + cidr);
            byte[] address = unmapped(literal(host)).getAddress();
            if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException("not a CIDR block: " + cidr);
            return new Block(address, prefix);
        }

        boolean contains(InetAddress address) {
            return covers(address.getAddress(), address.getAddress().length * 8);
        }

        boolean contains(Block other) {
            return other.prefix >= prefix && covers(other.network, other.prefix);
        }

        private boolean covers(byte[] candidate, int candidateBits) {
            if (candidate.length != network.length || candidateBits < prefix) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) if (candidate[i] != network[i]) return false;
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xff << (8 - rest)) & 0xff;
            return (candidate[full] & mask) == (network[full] & mask);
        }
    }
}
