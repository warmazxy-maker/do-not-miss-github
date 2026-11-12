package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Helpers for what tools hand to the model (F0 decision 7): fields are pruned by each tool, text
 * is cut only when it is very long, and lists are cut only by the structural safety cap, which is
 * always reported, never silent.
 */
public final class ToolOutput {
    /** Safety cap; tools bound their queries with required ranges long before this. */
    public static final int MAX_ITEMS = 100;
    public static final int MAX_TEXT = 2000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ToolOutput() {
    }

    /** {"total": n, "items": [...]} with at most {@link #MAX_ITEMS} items, and the truncation if any. */
    public static <T> ToolResult list(ObjectMapper mapper, List<T> items, Function<T, Map<String, Object>> fields,
                                      String summaryForUser) {
        int total = items.size();
        List<Map<String, Object>> kept = items.stream().limit(MAX_ITEMS).map(fields).toList();
        ObjectNode data = mapper.createObjectNode();
        data.put("total", total);
        data.set("items", mapper.valueToTree(kept));
        if (total > MAX_ITEMS) {
            return ToolResult.ok(data, summaryForUser, new ToolResult.Truncation(MAX_ITEMS, total));
        }
        return ToolResult.ok(data, summaryForUser);
    }

    public static ToolResult object(ObjectMapper mapper, Object value, String summaryForUser) {
        JsonNode data = mapper.valueToTree(value);
        return ToolResult.ok(data, summaryForUser);
    }

    /** Null and blank become null so empty fields can be left out; long text is cut with a marker. */
    public static String clip(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) + "…（后面省略）" : trimmed;
    }

    public static String time(LocalDateTime value) {
        return value == null ? null : value.format(TIME);
    }

    public static String time(Instant value, ZoneId zone) {
        return value == null ? null : LocalDateTime.ofInstant(value, zone).format(TIME);
    }

    /** Inclusive date range from required {@code from}/{@code to} arguments (already schema-checked). */
    public record DateRange(LocalDate from, LocalDate to) {
        public static DateRange of(JsonNode arguments) {
            LocalDate from = LocalDate.parse(arguments.path("from").asText());
            LocalDate to = LocalDate.parse(arguments.path("to").asText());
            if (from.isAfter(to)) {
                throw ApiException.badRequest("开始日期 " + from + " 晚于结束日期 " + to);
            }
            return new DateRange(from, to);
        }

        public boolean contains(LocalDateTime value) {
            return value != null && !value.toLocalDate().isBefore(from) && !value.toLocalDate().isAfter(to);
        }

        public String label() {
            return from + " 到 " + to;
        }
    }
}
