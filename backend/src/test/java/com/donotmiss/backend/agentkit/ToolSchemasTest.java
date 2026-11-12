package com.donotmiss.backend.agentkit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static com.donotmiss.backend.agentkit.AgentKitFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemasTest {
    private static final JsonNode RANGE = json("""
            {"type":"object","properties":{
              "from":{"type":"string","format":"date"},
              "status":{"type":"string","enum":["ACTIVE","DONE"]},
              "limit":{"type":"integer","minimum":1,"maximum":10}},
             "required":["from"],"additionalProperties":false}
            """);

    @Test
    void acceptsSupportedSchemaAndRejectsUnknownKeywords() {
        assertThat(ToolSchemas.problemsIn(RANGE)).isEmpty();
        assertThat(ToolSchemas.problemsIn(json("{\"type\":\"string\"}")))
                .anyMatch(problem -> problem.contains("top level"));
        assertThat(ToolSchemas.problemsIn(json("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"x\"}}}")))
                .anyMatch(problem -> problem.contains("pattern"));
        assertThat(ToolSchemas.problemsIn(json("{\"type\":\"object\",\"properties\":{},\"required\":[\"ghost\"]}")))
                .anyMatch(problem -> problem.contains("ghost"));
    }

    @Test
    void validArgumentsPass() {
        assertThat(ToolSchemas.validate(RANGE, json("{\"from\":\"2026-11-01\",\"status\":\"ACTIVE\",\"limit\":3}"))).isEmpty();
    }

    @Test
    void reportsEachProblemInPlainWords() {
        assertThat(ToolSchemas.validate(RANGE, json("{}"))).containsExactly("缺少 from");
        assertThat(ToolSchemas.validate(RANGE, json("{\"from\":\"上个月\"}")))
                .containsExactly("from 应该是 YYYY-MM-DD 格式的日期");
        assertThat(ToolSchemas.validate(RANGE, json("{\"from\":\"2026-11-01\",\"status\":\"X\"}")))
                .singleElement().asString().contains("只能是");
        assertThat(ToolSchemas.validate(RANGE, json("{\"from\":\"2026-11-01\",\"limit\":11}")))
                .containsExactly("limit 不能大于 10");
        assertThat(ToolSchemas.validate(RANGE, json("{\"from\":\"2026-11-01\",\"userId\":\"someone\"}")))
                .containsExactly("不认识的参数 userId");
        assertThat(ToolSchemas.validate(RANGE, json("{\"from\":20261101}")))
                .containsExactly("from 应该是 文字");
    }
}
