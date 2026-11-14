package ma.jomhor.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;

/** The client IP for per-IP limits (OTP sends, queue joins). */
public final class ClientIp {

    private ClientIp() {}

    /**
     * The peer address as Tomcat's RemoteIpValve left it: X-Forwarded-For is applied only when the peer matches
     * server.tomcat.remoteip.internal-proxies, so a client-sent header from anywhere else is ignored.
     * Parsed as an IP literal (no DNS); anything else is a misconfigured proxy → 400. IPv6 is bucketed by /64
     * (low 64 bits zeroed): one subscriber usually holds a whole /64, so per-address buckets would be free to rotate.
     */
    public static String of(HttpServletRequest request) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(request.getRemoteAddr());
        } catch (IllegalArgumentException e) {
            throw new ApiException("BAD_REQUEST", HttpStatus.BAD_REQUEST, "Unresolvable client address");
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, 8, 16, (byte) 0);
            try {
                address = InetAddress.getByAddress(bytes); // raw bytes: no lookup, no scope id
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e); // only thrown for a wrong length
            }
        }
        return address.getHostAddress();
    }
}
