package com.gitai.dashboard.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Git AI authorship notes. Git AI CLI totals remain the authoritative count for changed lines. */
@Component
public class GitAiNoteParser {
    private static final Pattern ATTRIBUTION_LINE = Pattern.compile(
            "^\\s*((?:s_[A-Za-z0-9_.-]+::t_[A-Za-z0-9_.-]+)|(?:h_[A-Za-z0-9_.-]+))\\s+(\\d+)(?:-(\\d+))?\\s*$");
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedNote parse(String rawNote) {
        if (rawNote == null || rawNote.isBlank()) {
            return ParsedNote.empty();
        }
        String[] sections = rawNote.split("(?m)^---\\s*$", 2);
        Map<String, EnumSet<Origin>> lineOrigins = parseLineOrigins(sections[0]);
        Map<AgentIdentity, Integer> sessions = sections.length > 1 ? parseSessions(sections[1]) : Map.of();

        long aiOnly = 0;
        long humanOnly = 0;
        long mixed = 0;
        for (EnumSet<Origin> origins : lineOrigins.values()) {
            if (origins.contains(Origin.AI) && origins.contains(Origin.HUMAN)) mixed++;
            else if (origins.contains(Origin.AI)) aiOnly++;
            else if (origins.contains(Origin.HUMAN)) humanOnly++;
        }
        return new ParsedNote(aiOnly, humanOnly, mixed, sessions);
    }

    private Map<String, EnumSet<Origin>> parseLineOrigins(String noteIndex) {
        Map<String, EnumSet<Origin>> result = new HashMap<>();
        String currentFile = "";
        for (String rawLine : noteIndex.split("\\R")) {
            if (!rawLine.isBlank() && !Character.isWhitespace(rawLine.charAt(0))) {
                currentFile = rawLine.trim();
                continue;
            }
            Matcher matcher = ATTRIBUTION_LINE.matcher(rawLine);
            if (!matcher.matches()) continue;
            Origin origin = matcher.group(1).startsWith("s_") ? Origin.AI : Origin.HUMAN;
            int start = Integer.parseInt(matcher.group(2));
            int end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
            // Notes contain real file ranges. A guard prevents malformed metadata from exhausting memory.
            if (currentFile.isBlank() || end < start || end - start > 500_000) continue;
            for (int line = start; line <= end; line++) {
                result.computeIfAbsent(currentFile + ":" + line, ignored -> EnumSet.noneOf(Origin.class)).add(origin);
            }
        }
        return result;
    }

    private Map<AgentIdentity, Integer> parseSessions(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode sessionNodes = root.path("sessions");
            if (!sessionNodes.isObject()) return Map.of();
            Map<AgentIdentity, Integer> result = new LinkedHashMap<>();
            sessionNodes.fields().forEachRemaining(entry -> {
                JsonNode agent = entry.getValue().path("agent_id");
                result.merge(new AgentIdentity(textOrUnknown(agent.path("tool")), textOrUnknown(agent.path("model"))), 1, Integer::sum);
            });
            return result;
        } catch (Exception ignored) {
            // The dashboard can still display CLI-derived attribution totals when only optional session metadata is malformed.
            return Map.of();
        }
    }

    private String textOrUnknown(JsonNode node) {
        String value = node.asText("").trim();
        return value.isBlank() ? "unknown" : value;
    }

    private enum Origin { AI, HUMAN }

    public record AgentIdentity(String tool, String model) {}
    public record ParsedNote(long aiOnlyLines, long humanOnlyLines, long mixedLines,
                             Map<AgentIdentity, Integer> sessionCounts) {
        public static ParsedNote empty() {
            return new ParsedNote(0, 0, 0, Map.of());
        }
    }
}