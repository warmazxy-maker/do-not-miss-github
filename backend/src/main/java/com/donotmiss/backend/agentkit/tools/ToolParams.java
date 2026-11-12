package com.donotmiss.backend.agentkit.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;

/** Parameter schemas shared by the first tools; written as JSON so they read like what the model sees. */
final class ToolParams {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final JsonNode NONE = parse("""
            {"type": "object", "properties": {}, "additionalProperties": false}
            """);

    static final JsonNode DATE_RANGE = parse("""
            {
              "type": "object",
              "properties": {
                "from": {"type": "string", "format": "date", "description": "开始日期（含），YYYY-MM-DD"},
                "to": {"type": "string", "format": "date", "description": "结束日期（含），YYYY-MM-DD"}
              },
              "required": ["from", "to"],
              "additionalProperties": false
            }
            """);

    private ToolParams() {
    }

    static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
