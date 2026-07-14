package com.repoguard.agent.external;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OutboundEndpointPolicy {

    private final OutboundEndpointProperties properties;
    private final HostResolver resolver;

    @Autowired
    public OutboundEndpointPolicy(OutboundEndpointProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    OutboundEndpointPolicy(OutboundEndpointProperties properties, HostResolver resolver) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public URI validate(OutboundEndpointType type, String endpoint) {
        Objects.requireNonNull(type, "type");
        URI uri = parse(type, endpoint);
        String scheme = lower(uri.getScheme());
        String host = normalizeHost(uri.getHost());
        if (!allowedSchemes(type).contains(scheme)) {
            throw rejected(type, "scheme is not allowed");
        }
        if (!StringUtils.hasText(host) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw rejected(type, "endpoint authority is invalid");
        }
        if (!matchesAny(host, properties.allowedHosts(type))) {
            throw rejected(type, "host is not allowlisted");
        }
        int port = effectivePort(type, scheme, uri.getPort());
        if (!properties.allowedPorts(type).contains(port)) {
            throw rejected(type, "port is not allowlisted");
        }
        validateResolvedAddresses(type, host);
        return uri;
    }

    public URI validateConfiguration(OutboundEndpointType type, String endpoint) {
        try {
            return validate(type, endpoint);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
    }

    public boolean sameOrigin(OutboundEndpointType type, String first, String second) {
        if (!StringUtils.hasText(first) || !StringUtils.hasText(second)) {
            return Objects.equals(trim(first), trim(second));
        }
        URI left = parse(type, first);
        URI right = parse(type, second);
        return lower(left.getScheme()).equals(lower(right.getScheme()))
            && normalizeHost(left.getHost()).equals(normalizeHost(right.getHost()))
            && effectivePort(type, lower(left.getScheme()), left.getPort())
                == effectivePort(type, lower(right.getScheme()), right.getPort());
    }

    private URI parse(OutboundEndpointType type, String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            throw rejected(type, "endpoint is empty");
        }
        String value = endpoint.trim();
        if (type == OutboundEndpointType.MYSQL && value.regionMatches(true, 0, "jdbc:", 0, 5)) {
            value = value.substring(5);
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                throw rejected(type, "endpoint must be absolute");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Rejected ")) {
                throw ex;
            }
            throw rejected(type, "endpoint is malformed");
        }
    }

    private void validateResolvedAddresses(OutboundEndpointType type, String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw rejected(type, "host cannot be resolved");
        }
        if (addresses.length == 0) {
            throw rejected(type, "host cannot be resolved");
        }
        boolean privateAllowed = matchesAny(host, properties.getPrivateNetworkAllowedHosts());
        if (!privateAllowed && Arrays.stream(addresses).anyMatch(this::isNonPublicAddress)) {
            throw rejected(type, "resolved address is not public");
        }
    }

    private boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isNonPublicIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            if ((first & 0xfe) == 0xfc) {
                return true;
            }
            if (isIpv4Mapped(bytes)) {
                return isNonPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
        }
        return false;
    }

    private boolean isNonPublicIpv4(byte[] address) {
        int first = Byte.toUnsignedInt(address[0]);
        int second = Byte.toUnsignedInt(address[1]);
        return first == 0 || first == 10 || first == 127
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || (first == 198 && (second == 18 || second == 19))
            || first >= 224;
    }

    private boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }

    private boolean matchesAny(String host, List<String> patterns) {
        return patterns.stream()
            .filter(StringUtils::hasText)
            .map(this::normalizeHost)
            .anyMatch(pattern -> pattern.equals(host)
                || (pattern.startsWith("*.") && host.endsWith(pattern.substring(1))
                    && host.length() > pattern.length() - 1));
    }

    private List<String> allowedSchemes(OutboundEndpointType type) {
        return switch (type) {
            case GITHUB, LLM, NOTIFICATION -> List.of("https");
            case MYSQL -> List.of("mysql");
            case RABBITMQ -> List.of("amqp", "amqps");
        };
    }

    private int effectivePort(OutboundEndpointType type, String scheme, int configuredPort) {
        if (configuredPort > 0) {
            return configuredPort;
        }
        return switch (scheme) {
            case "https" -> 443;
            case "amqps" -> 5671;
            case "mysql" -> 3306;
            case "amqp" -> 5672;
            default -> throw rejected(type, "port is missing");
        };
    }

    private String normalizeHost(String host) {
        if (!StringUtils.hasText(host)) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.contains(":")) {
            return value;
        }
        return IDN.toASCII(value);
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private IllegalArgumentException rejected(OutboundEndpointType type, String reason) {
        return new IllegalArgumentException("Rejected " + type.name().toLowerCase(Locale.ROOT) + " outbound endpoint: " + reason);
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
