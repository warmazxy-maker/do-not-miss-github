package com.donotmiss.backend.mcp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public class McpDtos {
    public record ToolContextRequest(
            String timezone,
            String clientNow,
            Double latitude,
            Double longitude,
            String locationText
    ) {
    }

    public record TimeToolResult(
            String toolName,
            String timezone,
            OffsetDateTime serverTime,
            LocalDate localDate,
            String dayOfWeek
    ) {
    }

    public record LocationToolResult(
            String toolName,
            String source,
            Double latitude,
            Double longitude,
            String label,
            String queryText
    ) {
    }

    public record ToolContextResponse(
            TimeToolResult currentTime,
            LocationToolResult location,
            List<String> toolTrace
    ) {
    }
}
