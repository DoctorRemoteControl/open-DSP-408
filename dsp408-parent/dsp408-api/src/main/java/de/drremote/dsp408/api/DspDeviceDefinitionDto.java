package de.drremote.dsp408.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable, syntactically validated endpoint. Construction never performs DNS lookup. */
public record DspDeviceDefinitionDto(String id, String host, int port, String library, boolean autoConnect) {
    public DspDeviceDefinitionDto {
        id = normalizeId(id);
        host = normalizeHost(host);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("DSP port must be between 1 and 65535");
        }
        library = library == null ? "auto" : library.trim().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "fir408", "dsp408").contains(library)) {
            throw new IllegalArgumentException("DSP library must be auto, fir408 or dsp408");
        }
    }

    public static String normalizeId(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("DSP id must have 1..64 letters, digits, dots, underscores or dashes");
        }
        return normalized;
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isEmpty() || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("DSP host must be a DNS name, IPv4 or IPv6 literal without whitespace");
        }
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
            if (!host.contains(":")) {
                throw new IllegalArgumentException("Brackets require an IPv6 literal");
            }
        }
        if (host.contains(":")) {
            return normalizeIpv6(host);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.matches("[0-9.]+")) {
            return normalizeIpv4(host);
        }
        if (host.isEmpty() || host.length() > 253) {
            throw new IllegalArgumentException("Invalid DSP DNS host");
        }
        for (String label : host.split("\\.", -1)) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw new IllegalArgumentException("Invalid DSP DNS host");
            }
        }
        return host;
    }

    private static String normalizeIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 literal");
        }
        List<String> normalized = new ArrayList<>();
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                throw new IllegalArgumentException("Invalid IPv4 literal");
            }
            normalized.add(Integer.toString(Integer.parseInt(octet)));
        }
        return String.join(".", normalized);
    }

    private static String normalizeIpv6(String host) {
        if (host.contains(".")) {
            int split = host.lastIndexOf(':');
            String[] octets = normalizeIpv4(host.substring(split + 1)).split("\\.");
            host = host.substring(0, split + 1)
                    + Integer.toHexString(Integer.parseInt(octets[0]) * 256 + Integer.parseInt(octets[1]))
                    + ":" + Integer.toHexString(Integer.parseInt(octets[2]) * 256 + Integer.parseInt(octets[3]));
        }
        String[] halves = host.split("::", -1);
        if (halves.length > 2) {
            throw new IllegalArgumentException("Invalid IPv6 literal");
        }
        List<String> left = ipv6Groups(halves[0]);
        List<String> right = halves.length == 2 ? ipv6Groups(halves[1]) : List.of();
        int zeros = 8 - left.size() - right.size();
        if ((halves.length == 1 && zeros != 0) || (halves.length == 2 && zeros < 1)) {
            throw new IllegalArgumentException("Invalid IPv6 literal");
        }
        List<String> groups = new ArrayList<>(left);
        for (int i = 0; i < zeros; i++) groups.add("0");
        groups.addAll(right);
        // Expanded canonical spelling makes equivalent literal endpoints compare equal.
        return String.join(":", groups);
    }

    private static List<String> ipv6Groups(String value) {
        if (value.isEmpty()) return List.of();
        List<String> groups = new ArrayList<>();
        for (String group : value.split(":", -1)) {
            if (!group.matches("[0-9a-f]{1,4}")) {
                throw new IllegalArgumentException("Invalid IPv6 literal");
            }
            groups.add(Integer.toHexString(Integer.parseInt(group, 16)));
        }
        return groups;
    }
}
