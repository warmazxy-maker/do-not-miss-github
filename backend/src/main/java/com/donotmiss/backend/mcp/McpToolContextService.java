package com.donotmiss.backend.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class McpToolContextService {
    private final String defaultTimezone;
    private final String defaultLocation;

    public McpToolContextService(@Value("${app.mcp.default-timezone:Asia/Tokyo}") String defaultTimezone,
                                 @Value("${app.mcp.default-location:日本 大阪}") String defaultLocation) {
        this.defaultTimezone = defaultTimezone;
        this.defaultLocation = defaultLocation;
    }

    public McpDtos.ToolContextResponse resolve(McpDtos.ToolContextRequest request) {
        McpDtos.ToolContextRequest safeRequest = request == null
                ? new McpDtos.ToolContextRequest(null, null, null, null, null)
                : request;
        List<String> trace = new ArrayList<>();

        ZoneId zone = resolveZone(safeRequest.timezone(), trace);
        ZonedDateTime now = ZonedDateTime.now(zone);

        McpDtos.TimeToolResult time = new McpDtos.TimeToolResult(
                "mcp.current_time",
                zone.getId(),
                now.toOffsetDateTime(),
                now.toLocalDate(),
                now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA)
        );
        trace.add("mcp.current_time resolved server time in " + zone.getId());

        McpDtos.LocationToolResult location = resolveLocation(safeRequest, trace);
        return new McpDtos.ToolContextResponse(time, location, List.copyOf(trace));
    }

    public Map<String, Object> promptContext(McpDtos.ToolContextResponse context) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (context == null) {
            return data;
        }
        data.put("currentTime", context.currentTime());
        data.put("location", context.location());
        data.put("toolTrace", context.toolTrace());
        return data;
    }

    public String locationQuery(McpDtos.ToolContextResponse context) {
        if (context == null || context.location() == null || context.location().queryText() == null) {
            return "";
        }
        return context.location().queryText();
    }

    private ZoneId resolveZone(String requestedTimezone, List<String> trace) {
        String zoneText = isPresent(requestedTimezone) ? requestedTimezone.trim() : defaultTimezone;
        try {
            return ZoneId.of(zoneText);
        } catch (DateTimeException ex) {
            trace.add("mcp.current_time ignored invalid timezone: " + zoneText);
            return ZoneId.of(defaultTimezone);
        }
    }

    private McpDtos.LocationToolResult resolveLocation(McpDtos.ToolContextRequest request, List<String> trace) {
        if (isPresent(request.locationText())) {
            String label = request.locationText().trim();
            trace.add("mcp.location resolved from client text");
            return new McpDtos.LocationToolResult("mcp.location", "client-text", request.latitude(), request.longitude(), label, label);
        }

        if (request.latitude() != null && request.longitude() != null) {
            String label = String.format(Locale.ROOT, "lat %.5f, lng %.5f", request.latitude(), request.longitude());
            trace.add("mcp.location resolved from browser geolocation");
            return new McpDtos.LocationToolResult("mcp.location", "browser-geolocation", request.latitude(), request.longitude(), label, label);
        }

        trace.add("mcp.location resolved from default config");
        return new McpDtos.LocationToolResult("mcp.location", "default-config", null, null, defaultLocation, defaultLocation);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
