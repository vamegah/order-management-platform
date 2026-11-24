package com.example.omp.notification.webhook.delivery;

import java.net.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The same policy guards registration, dispatch and the actual connection's DNS result. */
@Component
public class WebhookDestinationPolicy {
    private final Set<String> testHosts;

    public WebhookDestinationPolicy(
            Environment environment,
            @Value("${webhook.destination.test-allowed-hosts:}") String allowed) {
        testHosts = new HashSet<>();
        for (String host : allowed.split(","))
            if (!host.isBlank()) testHosts.add(host.trim().toLowerCase(Locale.ROOT));
        var profiles = Set.of(environment.getActiveProfiles());
        if (!testHosts.isEmpty()
                && (profiles.contains("prod")
                        || profiles.contains("production")
                        || Collections.disjoint(
                                profiles, Set.of("local", "dev", "test", "e2e", "acceptance"))))
            throw new IllegalArgumentException(
                    "Test webhook destinations require an isolated development/test profile");
    }

    public URI validate(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (value.length() > 2048
                    || host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || host.contains("%")
                    || host.endsWith(".")
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || !("https".equals(uri.getScheme())
                            || ("http".equals(uri.getScheme())
                                    && testHosts.contains(host.toLowerCase(Locale.ROOT)))))
                throw new IllegalArgumentException();
            resolve(host);
            return uri;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Webhook destination is not permitted");
        }
    }

    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        return validateResolved(host, addresses);
    }

    public InetAddress[] validateResolved(String host, InetAddress[] addresses)
            throws UnknownHostException {
        if (addresses == null || addresses.length == 0 || addresses.length > 32)
            throw new UnknownHostException("Webhook destination denied");
        boolean test = testHosts.contains(host.toLowerCase(Locale.ROOT));
        for (InetAddress address : addresses)
            if (!test && !isPublic(address))
                throw new UnknownHostException("Webhook destination denied");
        return addresses.clone();
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return a != 0
                    && a != 10
                    && a != 127
                    && a < 224
                    && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254)
                    && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192
                            && (c == 168
                                    || (c == 0 && d == 0)
                                    || (c == 88 && d == 99)
                                    || (c == 0 && d == 2)))
                    && !(a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100)))
                    && !(a == 203 && c == 0 && d == 113);
        }
        // Only globally routed unicast IPv6; exclude documentation, transition and special ranges.
        return b.length == 16
                && (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20
                        && (b[1] & 255) == 0x01
                        && ((b[2] & 255) < 2 || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)))
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x3f && (b[1] & 255) == 0xff && (b[2] & 0xf0) == 0);
    }
}
