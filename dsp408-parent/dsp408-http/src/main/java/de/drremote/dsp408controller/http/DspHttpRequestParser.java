package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON and scalar validation shared by the HTTP route handlers. */
final class DspHttpRequestParser {
    private final ObjectMapper mapper;

    DspHttpRequestParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> readJsonBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() == 0) {
            return Collections.emptyMap();
        }
        return mapper.readValue(
                request.getInputStream(),
                new TypeReference<Map<String, Object>>() { }
        );
    }

    double requireDouble(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            throw missing(field);
        }
        return toDouble(value);
    }

    double requireDoubleAlias(Map<String, Object> body, String field, String alias) {
        Object value = body.containsKey(field) ? body.get(field) : body.get(alias);
        if (value == null) {
            throw missing(field);
        }
        return toDouble(value);
    }

    int requireInt(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            throw missing(field);
        }
        return toInt(value);
    }

    String requireString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw missing(field);
        }
        return String.valueOf(value).trim();
    }

    String optionalString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value).trim();
    }

    long optionalLong(Map<String, Object> body, String field, long fallback) {
        Object value = body.get(field);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    double optionalDouble(Map<String, Object> body, String field, double fallback) {
        Object value = body.get(field);
        return value == null ? fallback : toDouble(value);
    }

    List<Double> requireDoubleList(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            throw missing(field);
        }

        List<Double> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                values.add(toDouble(item));
            }
        } else {
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                for (String token : text.split("[,;\\s]+")) {
                    if (!token.isBlank()) {
                        values.add(Double.parseDouble(token));
                    }
                }
            }
        }

        if (values.isEmpty()) {
            throw new IllegalArgumentException("FIR coefficients are required.");
        }
        return values;
    }

    List<String> requireStringList(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("Missing or invalid " + field + ".");
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item == null || String.valueOf(item).isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-empty strings.");
            }
            values.add(String.valueOf(item).trim());
        }
        return List.copyOf(values);
    }

    boolean requireBoolean(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            throw missing(field);
        }
        return toBoolean(value);
    }

    boolean queryBoolean(HttpServletRequest request, String field, boolean fallback) {
        String value = request.getParameter(field);
        return value == null || value.isBlank() ? fallback : toBoolean(value);
    }

    int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return switch (text) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("Expected boolean value.");
        };
    }

    String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    static String normalizePath(String path) {
        return path == null || path.isBlank() ? "/" : path.trim();
    }

    private static IllegalArgumentException missing(String field) {
        return new IllegalArgumentException("Missing " + field + ".");
    }
}
