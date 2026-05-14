package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import com.google.gson.JsonArray;
import java.util.LinkedHashSet;
import java.util.Set;

final class FimStopSequences {

    private static final int MAX_STOP_SEQUENCES = 4;

    private FimStopSequences() {
    }

    static JsonArray create(AiCompletionRequest request) {
        Set<String> stops = new LinkedHashSet<>();
        if (request.lengthLevel() == AiCompletionLengthLevel.SINGLE_LINE) {
            add(stops, "\r\n");
            add(stops, "\n");
            add(stops, "\r");
        } else {
            String suffixLine = firstEffectiveSuffixLine(request.fimSuffix());
            add(stops, suffixLine.strip());
            add(stops, suffixLine);
            add(stops, "\n\n");
            add(stops, "\r\n\r\n");
        }

        JsonArray array = new JsonArray();
        for (String stop : stops) {
            array.add(stop);
        }
        return array;
    }

    private static void add(Set<String> stops, String stop) {
        if (stops.size() >= MAX_STOP_SEQUENCES || stop == null || stop.isEmpty()) {
            return;
        }
        stops.add(stop);
    }

    private static String firstEffectiveSuffixLine(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return "";
        }
        String normalized = suffix.replace("\r\n", "\n").replace("\r", "\n");
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return "";
    }
}
