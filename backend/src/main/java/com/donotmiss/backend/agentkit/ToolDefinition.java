package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * Everything the registry knows about a tool. Lives in code, next to the tool (F0 decision 1).
 *
 * @param name          what the model calls it; lower snake case, unique
 * @param version       bump when the description or parameters change, so runs can be tied to it
 * @param title         short Chinese name shown to students and in the ops panel
 * @param description   what the model reads to decide when to call it
 * @param parameters    JSON Schema of the arguments, limited to what {@link ToolSchemas} supports
 * @param permission    the student the call is for must have it
 * @param rateLimitTier charged per call when not null; a tool that calls a model must set one
 * @param timeout       how long one call may take
 */
public record ToolDefinition(
        String name,
        int version,
        String title,
        String description,
        JsonNode parameters,
        ToolRisk risk,
        Permission permission,
        RateLimitTier rateLimitTier,
        Duration timeout
) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration MODEL_TOOL_TIMEOUT = Duration.ofSeconds(30);
}
