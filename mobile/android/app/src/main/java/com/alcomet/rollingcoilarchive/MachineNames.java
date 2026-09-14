package com.alcomet.rollingcoilarchive;

import java.util.Locale;

final class MachineNames {
    private MachineNames() {}

    static String label(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        switch (code) {
            case "FM1": return "Foil Mill 1";
            case "FM2": return "Foil Mill 2";
            case "IM": return "Intermediate Mill";
            case "CM": return "CM";
            case "MINO": return "MINO";
            case "SMS": return "SMS Cold Mill";
            case "FM3": return "Foil Mill 3";
            case "COLD": return "Cold Mill";
            default: return code.isEmpty() ? "Unknown machine" : code;
        }
    }
}
