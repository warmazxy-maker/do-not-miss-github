package com.donotmiss.backend.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvalSetTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluationSetContainsValidGradedJudgments() throws Exception {
        List<RetrievalDtos.EvalCase> cases = objectMapper.readValue(
                getClass().getResourceAsStream("/retrieval-eval-set.json"),
                new TypeReference<>() {
                }
        );

        assertEquals(30, cases.size());
        assertEquals(30, cases.stream().map(RetrievalDtos.EvalCase::id).distinct().count());
        assertTrue(cases.stream().allMatch(item -> item.topK() != null && item.topK() == 5));
        assertTrue(cases.stream().allMatch(item -> item.judgments() != null && !item.judgments().isEmpty()));
        assertTrue(cases.stream().allMatch(item -> item.judgments().stream()
                .anyMatch(judgment -> judgment.relevance() == 3)));
        assertTrue(cases.stream().flatMap(item -> item.judgments().stream())
                .allMatch(judgment -> judgment.eventId() != null
                        && judgment.relevance() >= 1
                        && judgment.relevance() <= 3
                        && judgment.reason() != null
                        && !judgment.reason().isBlank()));

        long totalJudgments = cases.stream().mapToLong(item -> item.judgments().size()).sum();
        long multiAnswerCases = cases.stream().filter(item -> item.judgments().size() > 1).count();
        assertEquals(80, totalJudgments);
        assertEquals(25, multiAnswerCases);
        assertFalse(cases.stream().anyMatch(item -> item.need() == null || item.need().isBlank()));
    }
}
