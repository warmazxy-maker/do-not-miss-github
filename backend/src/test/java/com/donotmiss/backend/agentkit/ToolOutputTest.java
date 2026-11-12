package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.donotmiss.backend.agentkit.AgentKitFixtures.MAPPER;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolOutputTest {
    @Test
    void boundedListsComeBackWhole() {
        ToolResult result = ToolOutput.list(MAPPER, List.of(1, 2, 3), n -> Map.of("n", n), "三个");
        assertThat(result.truncation()).isNull();
        assertThat(result.messageForModel()).isNull();
        assertThat(result.data().path("total").asInt()).isEqualTo(3);
        assertThat(result.data().path("items")).hasSize(3);
    }

    @Test
    void theSafetyCapIsNeverSilent() {
        List<Integer> many = IntStream.range(0, 130).boxed().toList();
        ToolResult result = ToolOutput.list(MAPPER, many, n -> Map.of("n", n), "很多");
        assertThat(result.data().path("items")).hasSize(ToolOutput.MAX_ITEMS);
        assertThat(result.data().path("total").asInt()).isEqualTo(130);
        assertThat(result.truncation()).isEqualTo(new ToolResult.Truncation(100, 130));
        assertThat(result.messageForModel()).contains("前 100 条").contains("130").contains("如实告诉学生");
    }

    @Test
    void longTextIsCutWithAMarkerAndBlankBecomesNull() {
        assertThat(ToolOutput.clip("  ")).isNull();
        String cut = ToolOutput.clip("字".repeat(2500));
        assertThat(cut).startsWith("字".repeat(2000)).endsWith("（后面省略）");
    }

    @Test
    void dateRangesAreInclusiveAndOrdered() {
        ToolOutput.DateRange range = ToolOutput.DateRange.of(json("{\"from\":\"2026-11-01\",\"to\":\"2026-11-30\"}"));
        assertThat(range.contains(LocalDateTime.parse("2026-11-01T00:00"))).isTrue();
        assertThat(range.contains(LocalDateTime.parse("2026-11-30T23:59"))).isTrue();
        assertThat(range.contains(LocalDateTime.parse("2026-12-01T00:00"))).isFalse();
        assertThatThrownBy(() -> ToolOutput.DateRange.of(json("{\"from\":\"2026-12-01\",\"to\":\"2026-11-01\"}")))
                .isInstanceOf(ApiException.class);
    }
}
