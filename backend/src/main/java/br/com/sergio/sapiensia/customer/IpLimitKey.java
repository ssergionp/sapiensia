package br.com.sergio.sapiensia.customer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** The one way to build the per-IP counting key of a request limiter (SEC-014). */
public final class IpLimitKey {

    private IpLimitKey() {}

    /**
     * The per-IP counting key (SEC-014): IPv4 by address, IPv6 by its /64 prefix, because one subscriber usually gets a
     * whole /64 and could otherwise rotate through billions of addresses. An IPv6 that embeds an IPv4 counts as that
     * IPv4. Text that is not a valid literal is used as it is, so an attempt is always counted. No DNS lookup happens:
     * {@code ofLiteral} only parses.
     */
    public static String of(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return "";
        }
        if (clientIp.indexOf(':') < 0) {
            return clientIp;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(clientIp);
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, 8, bytes.length, (byte) 0);
            return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
        } catch (IllegalArgumentException | UnknownHostException e) {
            return clientIp;
        }
    }
}
