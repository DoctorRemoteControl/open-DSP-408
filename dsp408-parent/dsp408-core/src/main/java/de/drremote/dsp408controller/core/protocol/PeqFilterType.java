package de.drremote.dsp408controller.core.protocol;

import java.util.Locale;

public enum PeqFilterType {
    PEAK(0x00, "Peak"),
    LOW_SHELF(0x01, "Low Shelf"),
    HIGH_SHELF(0x02, "High Shelf"),
    LOW_PASS_6(0x03, "LP -6dB"),
    LOW_PASS_12(0x04, "LP -12dB"),
    HIGH_PASS_6(0x05, "HP -6dB"),
    HIGH_PASS_12(0x06, "HP -12dB"),
    ALL_PASS_1(0x07, "All Pass 1"),
    ALL_PASS_2(0x08, "All Pass 2");

    private final int rawValue;
    private final String displayName;

    PeqFilterType(int rawValue, String displayName) {
        this.rawValue = rawValue;
        this.displayName = displayName;
    }

    public int rawValue() {
        return rawValue;
    }

    public String displayName() {
        return displayName;
    }

    public static PeqFilterType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PEQ filter type is missing");
        }

        String normalized = value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");

        return switch (normalized) {
            case "peak" -> PEAK;
            case "lowshelf", "lowshelve" -> LOW_SHELF;
            case "highshelf", "highshelve" -> HIGH_SHELF;
            case "lp6", "lp6db", "lowpass6", "lowpass6db" -> LOW_PASS_6;
            case "lp12", "lp12db", "lowpass12", "lowpass12db" -> LOW_PASS_12;
            case "hp6", "hp6db", "highpass6", "highpass6db" -> HIGH_PASS_6;
            case "hp12", "hp12db", "highpass12", "highpass12db" -> HIGH_PASS_12;
            case "allpass1", "allpassone" -> ALL_PASS_1;
            case "allpass2", "allpasstwo" -> ALL_PASS_2;
            default -> throw new IllegalArgumentException(
                    "Unsupported PEQ filter type: " + value
            );
        };
    }

    public static PeqFilterType fromRaw(int rawValue) {
        for (PeqFilterType type : values()) {
            if (type.rawValue == rawValue) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported PEQ filter type raw value: " + rawValue);
    }
}
