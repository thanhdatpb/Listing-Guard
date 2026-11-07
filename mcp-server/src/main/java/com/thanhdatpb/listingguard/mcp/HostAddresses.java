package com.thanhdatpb.listingguard.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Classifies a host that is written as an IP literal or as {@code localhost}. Nothing here asks DNS: a name other than
 * {@code localhost} is simply "not a literal", so a host name can never be taken for a loopback or private address.
 */
final class HostAddresses {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    /** Dotted quad without leading zeros (so no octal reading such as 0177.0.0.1). */
    private static final Pattern IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");

    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private HostAddresses() {
    }

    /** The address, when {@code host} is an IPv4 or IPv6 literal (IPv6 may be in brackets); empty otherwise. */
    static Optional<InetAddress> literal(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        boolean v4 = IPV4.matcher(h).matches();
        boolean v6 = h.indexOf(':') >= 0 && IPV6.matcher(h).matches();
        if (!v4 && !v6) {
            return Optional.empty();
        }
        try {
            // A literal is parsed, never looked up.
            return Optional.of(InetAddress.getByName(h));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** {@code localhost} or a loopback IP literal. */
    static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        return literal(host).map(InetAddress::isLoopbackAddress).orElse(false);
    }

    /**
     * Loopback or a private network: 127.0.0.0/8, ::1, 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7 and
     * the shared address space 100.64/10 (RFC 6598, used by overlay networks). A wildcard address is never accepted.
     */
    static boolean isPrivateOrLoopback(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || isUniqueLocalIpv6(address)
                || isSharedAddressSpace(address);
    }

    /**
     * The ways a client may write this address as a host in a {@code Host} or {@code Origin} header: the dotted quad, or
     * an IPv6 address in brackets, expanded and compressed.
     */
    static List<String> hostForms(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            return List.of(address.getHostAddress());
        }
        String expanded = address.getHostAddress();
        int scope = expanded.indexOf('%');
        if (scope >= 0) {
            expanded = expanded.substring(0, scope);
        }
        return List.of("[" + expanded + "]", "[" + compressIpv6(b) + "]");
    }

    static String compressIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int i = 0; i < 8; i++) {
            if (groups[i] != 0) {
                continue;
            }
            int j = i;
            while (j < 8 && groups[j] == 0) {
                j++;
            }
            if (j - i > bestLength) {
                bestStart = i;
                bestLength = j - i;
            }
            i = j;
        }
        if (bestLength < 2) {
            bestStart = -1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private static boolean isSharedAddressSpace(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
    }
}
