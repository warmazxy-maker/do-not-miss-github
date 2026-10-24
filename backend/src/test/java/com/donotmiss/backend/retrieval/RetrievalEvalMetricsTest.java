package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.event.EventCategory;
import com.donotmiss.backend.mcp.McpDtos;
import com.donotmiss.backend.retrieval.eval.FrozenEventCorpus;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalTestAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvalMetricsTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-04-28T09:00:00+09:00");

    @Test
    void gradedRankingMetrics() {
        List<RetrievalDtos.RelevanceJudgment> judgments = List.of(
                new RetrievalDtos.RelevanceJudgment(10L, 3, "best"),
                new RetrievalDtos.RelevanceJudgment(20L, 1, "weak"));

        // weak answer first, best answer second, then an unjudged event
        RetrievalEvalService.RankMetrics metrics = RetrievalEvalService.rankMetrics(List.of(20L, 10L, 99L), judgments, 5);

        assertTrue(metrics.hit());
        assertEquals(1, metrics.bestRelevantRank());
        assertEquals(2, metrics.bestAnswerRank());
        assertEquals(0.5, metrics.reciprocalRank());
        assertEquals(0.4, metrics.precisionAtK());
        assertEquals(1.0, metrics.recallAtK());
        // DCG = 1/log2(2) + 7/log2(3) = 5.417; ideal = 7 + 1/log2(3) = 7.631
        assertEquals(0.71, metrics.ndcgAtK());
    }

    @Test
    void perfectOrderScoresOneAndEmptyListScoresZero() {
        List<RetrievalDtos.RelevanceJudgment> judgments = List.of(
                new RetrievalDtos.RelevanceJudgment(10L, 3, "best"),
                new RetrievalDtos.RelevanceJudgment(20L, 1, "weak"));

        assertEquals(1.0, RetrievalEvalService.rankMetrics(List.of(10L, 20L), judgments, 5).ndcgAtK());
        RetrievalEvalService.RankMetrics empty = RetrievalEvalService.rankMetrics(List.of(), judgments, 5);
        assertFalse(empty.hit());
        assertEquals(0.0, empty.ndcgAtK());
        assertNull(empty.bestAnswerRank());
    }

    @Test
    void onlyTheTopKCount() {
        List<RetrievalDtos.RelevanceJudgment> judgments = List.of(new RetrievalDtos.RelevanceJudgment(10L, 3, "best"));

        assertFalse(RetrievalEvalService.rankMetrics(List.of(1L, 2L, 3L, 4L, 5L, 10L), judgments, 5).hit());
    }

    @Test
    void rejectedEventsInTheTopKAreRepeatedMistakes() {
        assertEquals(List.of(58L), RetrievalEvalService.rejectedInTopK(List.of(1L, 58L, 3L), List.of(58L), 5));
        assertEquals(List.of(), RetrievalEvalService.rejectedInTopK(List.of(1L, 2L, 3L, 4L, 5L, 58L), List.of(58L), 5));
        assertEquals(List.of(), RetrievalEvalService.rejectedInTopK(List.of(58L), List.of(), 5));
    }

    @Test
    void zeroResultAndExpiredReturnRates() throws IOException {
        FrozenEventCorpus corpus = corpus();
        // event 1 ends 2026-06-05; a clock on 2026-07-01 makes it expired, 2026-04-28 does not
        OffsetDateTime afterEvent1 = OffsetDateTime.parse("2026-07-01T09:00:00+09:00");

        assertEquals(0.5, RetrievalEvalService.zeroResultRate(List.of(List.of(), List.of(1L))));
        assertEquals(0.0, RetrievalEvalService.expiredReturnRate(List.of(List.of(1L, 27L)), corpus, AS_OF));
        assertEquals(1.0, RetrievalEvalService.expiredReturnRate(List.of(List.of(1L), List.of()), corpus, afterEvent1));
        assertEquals(0.0, RetrievalEvalService.expiredReturnRate(List.of(List.of(), List.of()), corpus, afterEvent1));
    }

    @Test
    void frozenCorpusFiltersLikeTheDatabase() throws IOException {
        FrozenEventCorpus corpus = corpus();

        assertEquals(55, corpus.filtered(null, null, null).size());
        assertTrue(corpus.filtered("COMPANY", null, null).stream().allMatch(event -> event.getCategory() == EventCategory.COMPANY));
        assertTrue(corpus.filtered(null, null, "大阪").stream().allMatch(event -> event.getLocation().contains("大阪")));
        assertTrue(corpus.find(9999L).isEmpty());
    }

    /**
     * Retrieval over the frozen corpus needs no database and no live index: the live corpus is null
     * here, so touching it would throw.
     */
    @Test
    void retrievalRunsOverFrozenCorpusWithoutTheDatabase() throws IOException {
        FrozenEventCorpus corpus = corpus();
        HybridEventRetrievalService retrieval = new HybridEventRetrievalService(null);

        List<RetrievedEvent> events = retrieval.retrieve("Java 后端", "COMPANY", null, null,
                RetrievalEvalService.neutralProfile(), 10, clock(AS_OF), corpus);

        assertFalse(events.isEmpty());
        assertTrue(events.stream().allMatch(item -> corpus.find(item.event().getId()).isPresent()));
        assertTrue(events.stream().allMatch(item -> item.event().getCategory() == EventCategory.COMPANY));
        assertTrue(events.stream().allMatch(item -> item.finalScore() > 0), "nothing is expired at asOf");
    }

    private static McpDtos.ToolContextResponse clock(OffsetDateTime at) {
        return new McpDtos.ToolContextResponse(
                new McpDtos.TimeToolResult("eval.fixed_clock", at.getOffset().getId(), at, at.toLocalDate(),
                        DayOfWeek.from(at).name()),
                null, List.of());
    }

    private FrozenEventCorpus corpus() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/retrieval-eval/events-v1.json")) {
            return RetrievalEvalTestAccess.readCorpus(new ObjectMapper(), input.readAllBytes(), "no-vector");
        }
    }
}
