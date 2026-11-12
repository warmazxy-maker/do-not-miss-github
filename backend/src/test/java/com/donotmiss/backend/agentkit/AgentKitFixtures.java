package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.ratelimit.RateLimitStore;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small fakes shared by the agent-kit tests. */
final class AgentKitFixtures {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentKitFixtures() {
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    static final JsonNode NO_PARAMS = json("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

    interface Body {
        ToolResult run(JsonNode arguments, ToolCaller caller) throws Exception;
    }

    static AgentTool tool(String name, ToolRisk risk, JsonNode params, Duration timeout, RateLimitTier tier, Body body) {
        ToolDefinition definition = new ToolDefinition(name, 1, "工具" + name, "测试工具 " + name, params, risk,
                Permission.STUDENT_FEATURES, tier, timeout);
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolCaller caller) throws Exception {
                return body.run(arguments, caller);
            }
        };
    }

    static AgentTool readTool(String name, Body body) {
        return tool(name, ToolRisk.readOnly(), NO_PARAMS, Duration.ofSeconds(2), null, body);
    }

    static AgentSpec agent(String name, List<String> tools, int maxSteps) {
        return new AgentSpec() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String title() {
                return name;
            }

            @Override
            public String promptVersion() {
                return "test-v1";
            }

            @Override
            public String systemPrompt() {
                return "测试";
            }

            @Override
            public List<String> toolNames() {
                return tools;
            }

            @Override
            public int maxSteps() {
                return maxSteps;
            }

            @Override
            public RateLimitTier rateLimitTier() {
                return RateLimitTier.AGENT_TURN;
            }
        };
    }

    static ToolPermissions students() {
        return userId -> "student".equals(userId) ? Set.of(Permission.STUDENT_FEATURES) : Set.of();
    }

    /** Counts only; enough for the per-minute window checks the executor triggers. */
    static class CountingStore implements RateLimitStore {
        final Map<String, Long> counters = new HashMap<>();

        @Override
        public long increment(String key, Duration ttl) {
            return counters.merge(key, 1L, Long::sum);
        }

        @Override
        public boolean acquire(String key, int max, Duration ttl) {
            return true;
        }

        @Override
        public void release(String key) {
        }

        @Override
        public long secondsToLive(String key) {
            return -1;
        }

        @Override
        public void put(String key, Duration ttl) {
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public void incrementField(String key, String field, Duration ttl) {
        }

        @Override
        public Map<String, Long> fields(String key) {
            return Map.of();
        }

        @Override
        public void incrementScore(String key, String member, Duration ttl) {
        }

        @Override
        public List<Map.Entry<String, Long>> top(String key, int limit) {
            return List.of();
        }
    }
}
