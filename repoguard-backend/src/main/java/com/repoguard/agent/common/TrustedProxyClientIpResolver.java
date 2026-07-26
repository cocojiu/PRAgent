package com.repoguard.agent.common;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TrustedProxyClientIpResolver {

    public static final String REAL_IP_HEADER = "X-Real-IP";
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final String IGNORED_COUNTER = "repoguard.security.forwarded_client_ip_ignored";
    private static final int MAX_HEADER_LENGTH = 1024;
    private static final int MAX_ADDRESS_LENGTH = 45;

    private final List<TrustedNetwork> trustedNetworks;
    private final MeterRegistry meterRegistry;

    public TrustedProxyClientIpResolver(TrustedProxyProperties properties, MeterRegistry meterRegistry) {
        this.trustedNetworks = parseNetworks(properties.getNetworks());
        this.meterRegistry = meterRegistry;
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String realIp = request.getHeader(REAL_IP_HEADER);
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (!isTrustedProxy(peer)) {
            countIgnored("untrusted_proxy", realIp, forwardedFor);
            return peer;
        }
        InetAddress forwarded = rightmostAddress(realIp);
        if (forwarded == null) {
            forwarded = rightmostAddress(forwardedFor);
        }
        if (forwarded == null) {
            countIgnored("malformed_header", realIp, forwardedFor);
            return peer;
        }
        return forwarded.getHostAddress();
    }

    public boolean isTrustedProxy(String address) {
        InetAddress parsed = parseAddress(address);
        if (parsed == null) {
            return false;
        }
        byte[] candidate = normalize(parsed.getAddress());
        for (TrustedNetwork network : trustedNetworks) {
            if (network.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void countIgnored(String reason, String realIp, String forwardedFor) {
        if (StringUtils.hasText(realIp) || StringUtils.hasText(forwardedFor)) {
            meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
        }
    }

    private static InetAddress rightmostAddress(String header) {
        if (!StringUtils.hasText(header) || header.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        String[] entries = header.split(",");
        for (int index = entries.length - 1; index >= 0; index--) {
            String candidate = entries[index].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            return parseAddress(candidate);
        }
        return null;
    }

    private static InetAddress parseAddress(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ADDRESS_LENGTH) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = character == '.'
                || character == ':'
                || (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
            if (!allowed) {
                return null;
            }
        }
        if (!hasStrictFormat(value)) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean hasStrictFormat(String value) {
        int lastColon = value.lastIndexOf(':');
        if (lastColon < 0) {
            return isStrictIpv4(value);
        }
        int firstDot = value.indexOf('.');
        return firstDot < 0 || (firstDot > lastColon && isStrictIpv4(value.substring(lastColon + 1)));
    }

    private static boolean isStrictIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            for (int index = 0; index < octet.length(); index++) {
                char character = octet.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static byte[] normalize(byte[] address) {
        if (address.length != 16) {
            return address;
        }
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return address;
            }
        }
        if (address[10] != (byte) 0xFF || address[11] != (byte) 0xFF) {
            return address;
        }
        return new byte[] {address[12], address[13], address[14], address[15]};
    }

    private static List<TrustedNetwork> parseNetworks(List<String> configured) {
        List<TrustedNetwork> networks = new ArrayList<>();
        for (String entry : configured) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            networks.add(TrustedNetwork.parse(entry.trim()));
        }
        return List.copyOf(networks);
    }

    private static final class TrustedNetwork {

        private final byte[] prefix;
        private final int prefixLength;

        private TrustedNetwork(byte[] prefix, int prefixLength) {
            this.prefix = prefix;
            this.prefixLength = prefixLength;
        }

        private static TrustedNetwork parse(String value) {
            int separator = value.indexOf('/');
            InetAddress address = parseAddress(separator < 0 ? value : value.substring(0, separator));
            if (address == null) {
                throw new IllegalStateException(
                    "app.security.trusted-proxy.networks contains an invalid network: " + value
                );
            }
            byte[] prefix = normalize(address.getAddress());
            int maxPrefixLength = prefix.length * 8;
            int prefixLength = maxPrefixLength;
            if (separator >= 0) {
                try {
                    prefixLength = Integer.parseInt(value.substring(separator + 1).trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalStateException(
                        "app.security.trusted-proxy.networks contains an invalid network: " + value,
                        ex
                    );
                }
            }
            if (prefixLength < 0 || prefixLength > maxPrefixLength) {
                throw new IllegalStateException(
                    "app.security.trusted-proxy.networks contains an out-of-range prefix: " + value
                );
            }
            return new TrustedNetwork(prefix, prefixLength);
        }

        private boolean contains(byte[] candidate) {
            if (candidate.length != prefix.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != prefix[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (prefix[fullBytes] & mask);
        }
    }
}
