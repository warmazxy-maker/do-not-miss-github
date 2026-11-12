package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.ratelimit.RateLimitTier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.donotmiss.backend.agentkit.AgentKitFixtures.NO_PARAMS;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.agent;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.json;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.readTool;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    private static final AgentKitFixtures.Body NOTHING = (arguments, caller) -> ToolResult.ok(null, "ok");

    @Test
    void collectsToolsAndHonoursTheDisabledList() {
        ToolRegistry registry = new ToolRegistry(List.of(readTool("b_tool", NOTHING), readTool("a_tool", NOTHING)),
                List.of("b_tool", " ", "ghost"));
        assertThat(registry.all()).extracting(t -> t.definition().name()).containsExactly("a_tool", "b_tool");
        assertThat(registry.isEnabled("a_tool")).isTrue();
        assertThat(registry.isEnabled("b_tool")).isFalse();
        assertThat(registry.isEnabled("ghost")).isFalse();
    }

    @Test
    void brokenDefinitionsStopTheStart() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(readTool("same", NOTHING), readTool("same", NOTHING)), List.of()))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new ToolRegistry(List.of(readTool("Bad-Name", NOTHING)), List.of()))
                .hasMessageContaining("name must match");
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("bad_schema", ToolRisk.readOnly(),
                json("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"oneOf\":[]}}}"),
                Duration.ofSeconds(1), null, NOTHING)), List.of()))
                .hasMessageContaining("oneOf");
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("uses_model",
                new ToolRisk(ToolRisk.DataEffect.READ, ToolRisk.ModelUsage.CALLS_MODEL),
                NO_PARAMS, Duration.ofSeconds(1), null, NOTHING)), List.of()))
                .hasMessageContaining("rate-limit tier");
    }

    @Test
    void modelToolWithATierIsAccepted() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("uses_model",
                new ToolRisk(ToolRisk.DataEffect.READ, ToolRisk.ModelUsage.CALLS_MODEL),
                NO_PARAMS, Duration.ofSeconds(1), RateLimitTier.AI_LIGHT, NOTHING)), List.of());
        assertThat(registry.contains("uses_model")).isTrue();
    }

    @Test
    void agentsMayOnlyListExistingToolsAndAreIndexedBothWays() {
        ToolRegistry tools = new ToolRegistry(List.of(readTool("shared", NOTHING), readTool("own", NOTHING)), List.of());
        AgentRegistry agents = new AgentRegistry(List.of(
                agent("alpha", List.of("shared", "own"), 3),
                agent("beta", List.of("shared"), 3)), tools);
        assertThat(agents.agentsUsing("shared")).containsExactly("alpha", "beta");
        assertThat(agents.agentsUsing("own")).containsExactly("alpha");

        assertThatThrownBy(() -> new AgentRegistry(List.of(agent("gamma", List.of("missing"), 3)), tools))
                .hasMessageContaining("unknown tool 'missing'");
        assertThatThrownBy(() -> new AgentRegistry(List.of(agent("delta", List.of(), 11)), tools))
                .hasMessageContaining("maxSteps");
    }

    @Test
    void riskDimensionsAreListedInOrder() {
        assertThat(ToolRisk.readOnly().dimensions()).containsExactly(
                java.util.Map.entry("数据", "只读"), java.util.Map.entry("模型", "不调用模型"));
    }
}
