package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventReviewStatus;
import com.donotmiss.backend.retrieval.eval.FrozenEventCorpus;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalSet;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalTestAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The versioned evaluation set and the frozen corpus it is judged against must stay consistent. */
class RetrievalEvalSetTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluationSetIsVersionedAndContainsValidGradedJudgments() throws Exception {
        RetrievalEvalSet set = RetrievalEvalTestAccess.readEvalSet(objectMapper, bytes("/retrieval-eval/retrieval-eval-set.json"));
        assertTrue(set.version().startsWith("retrieval-eval-v"));
        assertEquals(set.cases().size(), set.cases().stream().map(RetrievalDtos.EvalCase::id).distinct().count());
        // Cases exported from feedback (B4) only say what must not appear; they are checked separately.
        assertTrue(set.cases().stream().filter(item -> item.source() != null && item.source().startsWith("feedback:"))
                .allMatch(item -> item.rejectedEventIds() != null && !item.rejectedEventIds().isEmpty()
                        && item.need() != null && !item.need().isBlank()));
        List<RetrievalDtos.EvalCase> cases = set.cases().stream()
                .filter(item -> item.source() == null || !item.source().startsWith("feedback:"))
                .toList();

        assertEquals(30, cases.size());
        assertEquals(30, cases.stream().map(RetrievalDtos.EvalCase::id).distinct().count());
        assertTrue(cases.stream().allMatch(item -> item.topK() != null && item.topK() == 5));
        assertTrue(cases.stream().allMatch(item -> item.judgments() != null && !item.judgments().isEmpty()));
        assertTrue(cases.stream().allMatch(item -> item.judgments().stream().anyMatch(judgment -> judgment.relevance() == 3)));
        assertTrue(cases.stream().flatMap(item -> item.judgments().stream())
                .allMatch(judgment -> judgment.eventId() != null
                        && judgment.relevance() >= 1
                        && judgment.relevance() <= 3
                        && judgment.reason() != null
                        && !judgment.reason().isBlank()));
        assertEquals(80, cases.stream().mapToLong(item -> item.judgments().size()).sum());
        assertEquals(25, cases.stream().filter(item -> item.judgments().size() > 1).count());
        assertFalse(cases.stream().anyMatch(item -> item.need() == null || item.need().isBlank()));
    }

    @Test
    void everyJudgedEventExistsInTheFrozenCorpus() throws Exception {
        RetrievalEvalSet set = RetrievalEvalTestAccess.readEvalSet(objectMapper, bytes("/retrieval-eval/retrieval-eval-set.json"));
        FrozenEventCorpus corpus = RetrievalEvalTestAccess.readCorpus(objectMapper,
                bytes("/retrieval-eval/" + set.corpusVersion() + ".json"), "no-vector");

        Set<Long> judged = set.cases().stream().flatMap(item -> item.judgments().stream())
                .map(RetrievalDtos.RelevanceJudgment::eventId).collect(Collectors.toSet());
        set.cases().stream().filter(item -> item.rejectedEventIds() != null)
                .forEach(item -> judged.addAll(item.rejectedEventIds()));
        Set<Long> missing = judged.stream().filter(id -> corpus.find(id).isEmpty()).collect(Collectors.toSet());

        assertTrue(missing.isEmpty(), "judged events missing from corpus: " + missing);
    }

    /** If asOf were after an event's end, that event would silently score 0 and every metric would drop. */
    @Test
    void evaluationClockIsBeforeEveryFrozenEventEnds() throws Exception {
        RetrievalEvalSet set = RetrievalEvalTestAccess.readEvalSet(objectMapper, bytes("/retrieval-eval/retrieval-eval-set.json"));
        LocalDateTime clock = set.asOf().toLocalDateTime();

        List<EventEntity> events = corpus("no-vector").filtered(null, null, null);
        assertEquals(55, events.size());
        assertTrue(events.stream().allMatch(event -> event.getEndTime().isAfter(clock)),
                "asOf must precede every frozen event's end time");
        assertTrue(events.stream().allMatch(event -> event.getReviewStatus() == EventReviewStatus.APPROVED),
                "a frozen corpus holds published events only");
    }

    @Test
    void fingerprintPinsCorpusAndEmbeddingModel() throws Exception {
        FrozenEventCorpus a = corpus("openai:text-embedding-v4");
        FrozenEventCorpus b = corpus("openai:text-embedding-v4");
        FrozenEventCorpus otherModel = corpus("openai:text-embedding-v5");

        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), otherModel.fingerprint());
        assertTrue(a.indexName().startsWith("do_not_miss_eval_events_v1_"), a.indexName());
        assertNotEquals(a.indexName(), otherModel.indexName());
        assertNotEquals("do_not_miss_events", a.indexName());
    }

    @Test
    void frozenEventsKeepTheirQualityReports() throws Exception {
        FrozenEventCorpus corpus = corpus("no-vector");

        assertTrue(corpus.qualityReport(27L).isPresent());
        assertTrue(corpus.qualityReport(27L).get().getExtractedTagsJson().startsWith("["));
        assertTrue(corpus.qualityReport(1L).isEmpty());
        assertEquals(30, corpus.indexedEvents().stream().filter(item -> item.qualityReport() != null).count());
    }

    private FrozenEventCorpus corpus(String embeddingLabel) throws IOException {
        return RetrievalEvalTestAccess.readCorpus(objectMapper, bytes("/retrieval-eval/events-v1.json"), embeddingLabel);
    }

    private byte[] bytes(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return input.readAllBytes();
        }
    }
}
