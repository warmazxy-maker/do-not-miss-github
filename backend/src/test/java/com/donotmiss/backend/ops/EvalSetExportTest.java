package com.donotmiss.backend.ops;

import com.donotmiss.backend.retrieval.RetrievalDtos;
import com.donotmiss.backend.retrieval.eval.FrozenEventCorpus;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalSet;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalTestAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalSetExportTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode currentSet;
    private JsonNode currentCorpus;

    @BeforeEach
    void loadV1() throws IOException {
        currentSet = objectMapper.readTree(bytes("/retrieval-eval/retrieval-eval-set.json"));
        currentCorpus = objectMapper.readTree(bytes("/retrieval-eval/events-v1.json"));
    }

    @Test
    void newEventBumpsBothVersionsAndRoundTripsThroughTheFixtureParser() throws Exception {
        EvalSetExport.Result result = EvalSetExport.build(objectMapper, currentSet, currentCorpus,
                List.of(new EvalSetExport.ApprovedCase(12L, 58L, "想找 Spring Boot 线下活动", "工作日晚上要上课", "时间冲突")),
                Map.of(58L, liveEvent(58L, "2026-10-30T21:00:00")), TODAY);

        assertEquals("retrieval-eval-v2", result.evalSetVersion());
        assertEquals("events-v2", result.corpusVersion());
        assertTrue(result.corpusChanged());
        assertEquals(List.of("feedback-12"), result.newCaseIds());
        assertEquals(List.of(58L), result.addedEventIds());

        // The exported files must load exactly like committed fixtures.
        RetrievalEvalSet set = RetrievalEvalTestAccess.readEvalSet(objectMapper, objectMapper.writeValueAsBytes(result.evalSet()));
        FrozenEventCorpus corpus = RetrievalEvalTestAccess.readCorpus(objectMapper, objectMapper.writeValueAsBytes(result.corpus()), "no-vector");
        assertEquals(31, set.cases().size());
        assertEquals(56, corpus.size());
        RetrievalDtos.EvalCase added = set.cases().get(30);
        assertEquals("想找 Spring Boot 线下活动", added.need());
        assertEquals(List.of(58L), added.rejectedEventIds());
        assertTrue(added.judgments().isEmpty());
        assertEquals("feedback:candidate-12", added.source());
        assertTrue(added.note().contains("工作日晚上要上课"));
        assertTrue(corpus.find(58L).isPresent());
        assertEquals(2, result.evalSet().path("changelog").size());
    }

    @Test
    void exportedCaseNeverCarriesTheStudentId() throws Exception {
        EvalSetExport.Result result = EvalSetExport.build(objectMapper, currentSet, currentCorpus,
                List.of(new EvalSetExport.ApprovedCase(12L, 58L, "need", null, null)),
                Map.of(58L, liveEvent(58L, "2026-10-30T21:00:00")), TODAY);

        String json = objectMapper.writeValueAsString(result.evalSet());
        assertFalse(json.contains("demo-student"));
        assertFalse(json.contains("userId"));
    }

    @Test
    void frozenEventOnlyBumpsTheSetVersion() {
        EvalSetExport.Result result = EvalSetExport.build(objectMapper, currentSet, currentCorpus,
                List.of(new EvalSetExport.ApprovedCase(13L, 27L, "need", null, null)), Map.of(), TODAY);

        assertEquals("retrieval-eval-v2", result.evalSetVersion());
        assertEquals("events-v1", result.corpusVersion());
        assertFalse(result.corpusChanged());
    }

    @Test
    void alreadyExportedCasesAreSkipped() {
        EvalSetExport.Result first = EvalSetExport.build(objectMapper, currentSet, currentCorpus,
                List.of(new EvalSetExport.ApprovedCase(13L, 27L, "need", null, null)), Map.of(), TODAY);

        EvalSetExport.Result again = EvalSetExport.build(objectMapper, first.evalSet(), currentCorpus,
                List.of(new EvalSetExport.ApprovedCase(13L, 27L, "need", null, null)), Map.of(), TODAY);

        assertTrue(again.newCaseIds().isEmpty());
        assertEquals("retrieval-eval-v2", again.evalSetVersion(), "nothing new, no new version");
    }

    @Test
    void eventsThatCannotBeEvaluatedAreSkippedWithAWarning() {
        EvalSetExport.Result result = EvalSetExport.build(objectMapper, currentSet, currentCorpus,
                List.of(new EvalSetExport.ApprovedCase(14L, 70L, "need", null, null),
                        new EvalSetExport.ApprovedCase(15L, 71L, "need", null, null)),
                // 70 no longer exists; 71 ended before the evaluation clock (2026-04-28)
                Map.of(71L, liveEvent(71L, "2026-04-01T12:00:00")), TODAY);

        assertTrue(result.newCaseIds().isEmpty());
        assertEquals(2, result.warnings().size());
        assertEquals("retrieval-eval-v1", result.evalSetVersion());
    }

    @Test
    void versionBump() {
        assertEquals("retrieval-eval-v2", EvalSetExport.bump("retrieval-eval-v1"));
        assertEquals("events-v10", EvalSetExport.bump("events-v9"));
        assertEquals("custom-2", EvalSetExport.bump("custom"));
    }

    private ObjectNode liveEvent(Long id, String endTime) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("title", "后端工程实战夜");
        node.put("organizationName", "Kansai Career Tech");
        node.put("category", "COMPANY");
        node.put("startTime", endTime.substring(0, 11) + "19:00:00");
        node.put("endTime", endTime);
        node.put("location", "大阪梅田");
        node.put("content", "动手工作坊");
        node.put("benefitType", "SKILL");
        node.put("skill", "Java, Redis");
        node.putNull("moneyAmount");
        node.putNull("qualityReport");
        return node;
    }

    private byte[] bytes(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return input.readAllBytes();
        }
    }
}
