package dev.plume.search;

import java.util.Locale;

final class BroadcastRules {
    private BroadcastRules() {}

    static boolean isAntenna(String type, String service, boolean browsable, boolean locked) {
        return browsable && !locked && "SERVICE_TYPE_AUDIO_VIDEO".equals(service)
                && ("TYPE_ATSC_T".equals(type) || "TYPE_ATSC3_T".equals(type));
    }

    static boolean matches(String query, String title, String episode) {
        String needle = normalize(query);
        return !needle.isEmpty() && (normalize(title).contains(needle)
                || normalize(episode).contains(needle));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
