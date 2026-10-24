package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.ai.AiService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.mcp.McpDtos;
import com.donotmiss.backend.mcp.McpToolContextService;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.retrieval.eval.FrozenEventCorpus;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalFixtures;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalSet;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Offline recommendation evaluation over a frozen, versioned corpus.
 *
 * <p>Every input that could drift is pinned, so two runs of the same set version are comparable:</p>
 * <ul>
 *     <li>events: the frozen fixture and its own search index, never the live tables;</li>
 *     <li>clock: {@code asOf} from the set, so nothing "expires" between runs;</li>
 *     <li>profile: a neutral, empty profile, so results do not depend on who runs the evaluation.</li>
 * </ul>
 * <p>What is not pinned is the model: query rewrite and ranking call the configured LLM, so a
 * LIVE run varies a little between runs. Compare averages, not single cases.</p>
 *
 * <p>Two modes: {@code RETRIEVAL} measures the candidate list; {@code END_TO_END} also runs the
 * live ranking step and measures what the student would actually see.</p>
 */
@Service
public class RetrievalEvalService {
    private static final int DEFAULT_TOP_K = 5;
    /** Candidates handed to ranking in END_TO_END mode, as a live request does. */
    private static final int RANKING_CANDIDATES = 15;
    private static final double WIN_THRESHOLD = 0.001;

    private final RetrievalEvalFixtures fixtures;
    private final RetrievalTraceService retrievalTraceService;
    private final McpToolContextService mcpToolContextService;
    private final AiService aiService;
    private final OpenAiCompatibleLlmClient llmClient;

    public RetrievalEvalService(RetrievalEvalFixtures fixtures,
                                RetrievalTraceService retrievalTraceService,
                                McpToolContextService mcpToolContextService,
                                AiService aiService,
                                OpenAiCompatibleLlmClient llmClient) {
        this.fixtures = fixtures;
        this.retrievalTraceService = retrievalTraceService;
        this.mcpToolContextService = mcpToolContextService;
        this.aiService = aiService;
        this.llmClient = llmClient;
    }

    // ---- RETRIEVAL ----

    public RetrievalDtos.EvalResponse evaluate() {
        RetrievalEvalSet set = fixtures.evalSet();
        FrozenEventCorpus corpus = fixtures.corpus();
        McpDtos.ToolContextResponse clock = fixedClock(set.asOf());
        List<RetrievalDtos.EvalCaseResult> results = set.cases().stream()
                .map(evalCase -> evaluateRetrievalCase(evalCase, corpus, clock, false))
                .toList();
        return new RetrievalDtos.EvalResponse(runInfo("RETRIEVAL", set, corpus), summarize(results, corpus, set.asOf()), results);
    }

    public RetrievalDtos.EvalAbResponse evaluateAb() {
        RetrievalEvalSet set = fixtures.evalSet();
        FrozenEventCorpus corpus = fixtures.corpus();
        McpDtos.ToolContextResponse clock = fixedClock(set.asOf());
        List<RetrievalDtos.EvalAbCaseResult> results = set.cases().stream()
                .map(evalCase -> {
                    RetrievalDtos.EvalCaseResult current = evaluateRetrievalCase(evalCase, corpus, clock, false);
                    RetrievalDtos.EvalCaseResult baseline = evaluateRetrievalCase(evalCase, corpus, clock, true);
                    double ndcgDelta = round(current.ndcgAtK() - baseline.ndcgAtK());
                    double rrDelta = round(current.reciprocalRank() - baseline.reciprocalRank());
                    return new RetrievalDtos.EvalAbCaseResult(evalCase.id(), evalCase.need(), current.topK(),
                            current, baseline, winner(ndcgDelta, rrDelta), ndcgDelta, rrDelta, evalCase.note());
                })
                .toList();
        // Ranking averages only over cases that have a right answer (see summarize).
        List<RetrievalDtos.EvalCaseResult> current = results.stream().map(RetrievalDtos.EvalAbCaseResult::current)
                .filter(item -> !item.judgments().isEmpty()).toList();
        List<RetrievalDtos.EvalCaseResult> baseline = results.stream().map(RetrievalDtos.EvalAbCaseResult::baseline)
                .filter(item -> !item.judgments().isEmpty()).toList();
        RetrievalDtos.EvalAbSummary summary = new RetrievalDtos.EvalAbSummary(
                results.size(),
                hitRate(current), hitRate(baseline),
                average(current, RetrievalDtos.EvalCaseResult::recallAtK), average(baseline, RetrievalDtos.EvalCaseResult::recallAtK),
                average(current, RetrievalDtos.EvalCaseResult::ndcgAtK), average(baseline, RetrievalDtos.EvalCaseResult::ndcgAtK),
                average(current, RetrievalDtos.EvalCaseResult::reciprocalRank), average(baseline, RetrievalDtos.EvalCaseResult::reciprocalRank),
                (int) results.stream().filter(item -> "current".equals(item.winner())).count(),
                (int) results.stream().filter(item -> "baseline".equals(item.winner())).count(),
                (int) results.stream().filter(item -> "tie".equals(item.winner())).count());
        return new RetrievalDtos.EvalAbResponse(runInfo("RETRIEVAL_AB", set, corpus), summary, results);
    }

    private RetrievalDtos.EvalCaseResult evaluateRetrievalCase(RetrievalDtos.EvalCase evalCase,
                                                              FrozenEventCorpus corpus,
                                                              McpDtos.ToolContextResponse clock,
                                                              boolean baseline) {
        int topK = topK(evalCase);
        RetrievalTraceService.CorpusTrace trace = retrievalTraceService.traceOver(
                request(evalCase, topK), neutralProfile(), clock, corpus, baseline);
        List<RetrievalDtos.CandidateTrace> topCandidates = trace.response().candidates().stream().limit(topK).toList();
        List<RetrievalDtos.RelevanceJudgment> judgments = judgments(evalCase);
        RankMetrics metrics = rankMetrics(topCandidates.stream().map(RetrievalDtos.CandidateTrace::eventId).toList(), judgments, topK);

        List<String> expectedTerms = evalCase.expectedTerms() == null ? List.of() : evalCase.expectedTerms();
        List<String> matchedTerms = new ArrayList<>();
        topCandidates.forEach(candidate -> matchedTerms(candidate, expectedTerms).forEach(term -> {
            if (!matchedTerms.contains(term)) {
                matchedTerms.add(term);
            }
        }));
        List<Long> rejected = rejected(evalCase);
        return new RetrievalDtos.EvalCaseResult(
                evalCase.id(), evalCase.need(), topK, metrics.hit(), metrics.precisionAtK(), metrics.recallAtK(),
                metrics.ndcgAtK(), judgments, metrics.matchedEventIds(), metrics.bestRelevantRank(),
                metrics.bestAnswerRank(), metrics.reciprocalRank(), expectedTerms, List.copyOf(matchedTerms),
                topCandidates, trace.response().queryRewrite(), evalCase.note(), rejected,
                rejectedInTopK(topCandidates.stream().map(RetrievalDtos.CandidateTrace::eventId).toList(), rejected, topK));
    }

    // ---- END_TO_END ----

    public RetrievalDtos.EndToEndResponse evaluateEndToEnd() {
        RetrievalEvalSet set = fixtures.evalSet();
        FrozenEventCorpus corpus = fixtures.corpus();
        McpDtos.ToolContextResponse clock = fixedClock(set.asOf());
        List<RetrievalDtos.EndToEndCaseResult> results = set.cases().stream()
                .map(evalCase -> evaluateEndToEndCase(evalCase, corpus, clock))
                .toList();

        int modelIds = results.stream().mapToInt(item -> item.modelEventIds().size()).sum();
        int invalidIds = results.stream().mapToInt(item -> item.invalidModelEventIds().size()).sum();
        List<List<Long>> shown = results.stream().map(RetrievalDtos.EndToEndCaseResult::recommendedEventIds).toList();
        // A case built only from a "not relevant" click has no right answer, so it counts toward the
        // rejected-hit rate but not toward hit rate / NDCG / MRR.
        List<RetrievalDtos.EndToEndCaseResult> answered = results.stream().filter(item -> !item.judgments().isEmpty()).toList();
        List<RetrievalDtos.EndToEndCaseResult> withRejected = results.stream().filter(item -> !item.rejectedEventIds().isEmpty()).toList();
        RetrievalDtos.EndToEndSummary summary = new RetrievalDtos.EndToEndSummary(
                results.size(),
                rate(answered.stream().filter(RetrievalDtos.EndToEndCaseResult::hit).count(), answered.size()),
                round(answered.stream().mapToDouble(RetrievalDtos.EndToEndCaseResult::ndcgAtK).average().orElse(0)),
                round(answered.stream().mapToDouble(RetrievalDtos.EndToEndCaseResult::reciprocalRank).average().orElse(0)),
                round(answered.stream().mapToDouble(RetrievalDtos.EndToEndCaseResult::retrievalNdcgAtK).average().orElse(0)),
                zeroResultRate(shown),
                expiredReturnRate(shown, corpus, set.asOf()),
                rate(invalidIds, modelIds),
                rate(results.stream().filter(RetrievalDtos.EndToEndCaseResult::ruleFallback).count(), results.size()),
                withRejected.size(),
                rate(withRejected.stream().filter(item -> !item.rejectedInTopK().isEmpty()).count(), withRejected.size()));
        return new RetrievalDtos.EndToEndResponse(runInfo("END_TO_END", set, corpus), summary, results);
    }

    private RetrievalDtos.EndToEndCaseResult evaluateEndToEndCase(RetrievalDtos.EvalCase evalCase,
                                                                 FrozenEventCorpus corpus,
                                                                 McpDtos.ToolContextResponse clock) {
        int topK = topK(evalCase);
        UserMemoryDtos.Profile profile = neutralProfile();
        RetrievalTraceService.CorpusTrace trace = retrievalTraceService.traceOver(
                request(evalCase, RANKING_CANDIDATES), profile, clock, corpus, false);
        List<RetrievalDtos.RelevanceJudgment> judgments = judgments(evalCase);
        RankMetrics retrieval = rankMetrics(trace.events().stream().map(item -> item.event().getId()).toList(), judgments, topK);

        AiService.EvaluationRanking ranking = aiService.rankForEvaluation(
                trace.events(), profile,
                new AiDtos.EventRecommendationRequest(evalCase.need(), evalCase.category(), evalCase.benefitType(), evalCase.location(), null),
                trace.response().queryRewrite(), clock, corpus::qualityReport);
        List<Long> shown = ranking.recommendations().stream().map(item -> item.event().id()).toList();
        RankMetrics metrics = rankMetrics(shown, judgments, topK);
        Set<Long> candidateIds = trace.events().stream().map(item -> item.event().getId())
                .collect(java.util.stream.Collectors.toSet());
        List<Long> invalid = ranking.modelEventIds().stream().filter(id -> !candidateIds.contains(id)).distinct().toList();

        List<Long> rejected = rejected(evalCase);
        return new RetrievalDtos.EndToEndCaseResult(
                evalCase.id(), evalCase.need(), topK, metrics.hit(), metrics.ndcgAtK(), metrics.reciprocalRank(),
                metrics.bestAnswerRank(), shown, ranking.modelEventIds(), invalid, ranking.ruleFallback(),
                retrieval.ndcgAtK(), judgments, evalCase.note(), rejected, rejectedInTopK(shown, rejected, topK));
    }

    // ---- Metrics (pure functions) ----

    /** Ranking quality of an ordered list of event ids against graded judgments (0-3). */
    static RankMetrics rankMetrics(List<Long> rankedIds, List<RetrievalDtos.RelevanceJudgment> judgments, int topK) {
        Map<Long, Integer> relevance = new LinkedHashMap<>();
        judgments.forEach(item -> relevance.merge(item.eventId(), item.relevance(), Math::max));
        int maxRelevance = relevance.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Long> matched = new ArrayList<>();
        Integer bestRelevantRank = null;
        Integer bestAnswerRank = null;
        double dcg = 0;
        List<Long> top = rankedIds.stream().limit(topK).toList();
        for (int index = 0; index < top.size(); index++) {
            int rank = index + 1;
            int grade = relevance.getOrDefault(top.get(index), 0);
            if (grade <= 0) {
                continue;
            }
            matched.add(top.get(index));
            if (bestRelevantRank == null) {
                bestRelevantRank = rank;
            }
            if (bestAnswerRank == null && grade == maxRelevance) {
                bestAnswerRank = rank;
            }
            dcg += discountedGain(grade, rank);
        }
        double idealDcg = idealDcg(judgments, topK);
        return new RankMetrics(
                bestRelevantRank != null,
                round((double) matched.size() / topK),
                judgments.isEmpty() ? 0 : round((double) matched.size() / judgments.size()),
                idealDcg == 0 ? 0 : round(dcg / idealDcg),
                bestAnswerRank == null ? 0 : round(1.0 / bestAnswerRank),
                bestRelevantRank,
                bestAnswerRank,
                List.copyOf(matched));
    }

    record RankMetrics(boolean hit, double precisionAtK, double recallAtK, double ndcgAtK, double reciprocalRank,
                       Integer bestRelevantRank, Integer bestAnswerRank, List<Long> matchedEventIds) {
    }

    /** Rejected events that still made it into the top K: each one is a repeated mistake. */
    static List<Long> rejectedInTopK(List<Long> rankedIds, List<Long> rejected, int topK) {
        if (rejected.isEmpty()) {
            return List.of();
        }
        return rankedIds.stream().limit(topK).filter(rejected::contains).distinct().toList();
    }

    private static List<Long> rejected(RetrievalDtos.EvalCase evalCase) {
        return evalCase.rejectedEventIds() == null ? List.of()
                : evalCase.rejectedEventIds().stream().filter(id -> id != null).distinct().toList();
    }

    /** Share of cases where the student would see nothing. */
    static double zeroResultRate(List<List<Long>> shownPerCase) {
        return rate(shownPerCase.stream().filter(List::isEmpty).count(), shownPerCase.size());
    }

    /**
     * Share of shown events that had already ended at the evaluation clock. The frozen corpus is
     * chosen so that this should be 0; anything else means the clock or the expiry filter broke.
     */
    static double expiredReturnRate(List<List<Long>> shownPerCase, FrozenEventCorpus corpus, OffsetDateTime asOf) {
        LocalDateTime now = asOf.toLocalDateTime();
        long total = 0;
        long expired = 0;
        for (List<Long> shown : shownPerCase) {
            for (Long id : shown) {
                total++;
                EventEntity event = corpus.find(id).orElse(null);
                LocalDateTime end = event == null ? null : (event.getEndTime() == null ? event.getStartTime() : event.getEndTime());
                if (end != null && end.isBefore(now)) {
                    expired++;
                }
            }
        }
        return rate(expired, total);
    }

    private RetrievalDtos.EvalSummary summarize(List<RetrievalDtos.EvalCaseResult> results, FrozenEventCorpus corpus, OffsetDateTime asOf) {
        List<List<Long>> shown = results.stream()
                .map(item -> item.topCandidates().stream().map(RetrievalDtos.CandidateTrace::eventId).toList())
                .toList();
        List<RetrievalDtos.EvalCaseResult> answered = results.stream().filter(item -> !item.judgments().isEmpty()).toList();
        List<RetrievalDtos.EvalCaseResult> withRejected = results.stream().filter(item -> !item.rejectedEventIds().isEmpty()).toList();
        int hitCount = (int) answered.stream().filter(RetrievalDtos.EvalCaseResult::hit).count();
        return new RetrievalDtos.EvalSummary(
                results.size(),
                hitCount,
                rate(hitCount, answered.size()),
                average(answered, RetrievalDtos.EvalCaseResult::precisionAtK),
                average(answered, RetrievalDtos.EvalCaseResult::recallAtK),
                average(answered, RetrievalDtos.EvalCaseResult::ndcgAtK),
                average(answered, RetrievalDtos.EvalCaseResult::reciprocalRank),
                zeroResultRate(shown),
                expiredReturnRate(shown, corpus, asOf),
                withRejected.size(),
                rate(withRejected.stream().filter(item -> !item.rejectedInTopK().isEmpty()).count(), withRejected.size()));
    }

    // ---- Pinned inputs ----

    /** The live time tool's shape, with the time replaced by the set's {@code asOf}. */
    private McpDtos.ToolContextResponse fixedClock(OffsetDateTime asOf) {
        McpDtos.ToolContextResponse base = mcpToolContextService.resolve(null);
        McpDtos.TimeToolResult time = new McpDtos.TimeToolResult(
                "eval.fixed_clock",
                asOf.getOffset().getId(),
                asOf,
                asOf.toLocalDate(),
                asOf.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
        return new McpDtos.ToolContextResponse(time, base.location(), List.of("evaluation clock pinned to asOf=" + asOf));
    }

    static UserMemoryDtos.Profile neutralProfile() {
        return new UserMemoryDtos.Profile("评测用中性画像：没有历史记录和偏好。",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, List.of(), List.of());
    }

    private RetrievalDtos.EvalRunInfo runInfo(String mode, RetrievalEvalSet set, FrozenEventCorpus corpus) {
        return new RetrievalDtos.EvalRunInfo(mode, set.version(), set.labelStatus(), corpus.name(), corpus.fingerprint(),
                corpus.size(), set.asOf().toString(), llmClient.modeLabel(), Instant.now());
    }

    private static RetrievalDtos.TraceRequest request(RetrievalDtos.EvalCase evalCase, int limit) {
        return new RetrievalDtos.TraceRequest(evalCase.need(), evalCase.category(), evalCase.benefitType(),
                evalCase.location(), limit, null);
    }

    private static int topK(RetrievalDtos.EvalCase evalCase) {
        return evalCase.topK() == null ? DEFAULT_TOP_K : Math.max(evalCase.topK(), 1);
    }

    static List<RetrievalDtos.RelevanceJudgment> judgments(RetrievalDtos.EvalCase evalCase) {
        if (evalCase.judgments() != null && !evalCase.judgments().isEmpty()) {
            Map<Long, RetrievalDtos.RelevanceJudgment> normalized = new LinkedHashMap<>();
            for (RetrievalDtos.RelevanceJudgment item : evalCase.judgments()) {
                if (item == null || item.eventId() == null || item.relevance() <= 0) {
                    continue;
                }
                int relevance = Math.min(item.relevance(), 3);
                RetrievalDtos.RelevanceJudgment existing = normalized.get(item.eventId());
                if (existing == null || relevance > existing.relevance()) {
                    normalized.put(item.eventId(), new RetrievalDtos.RelevanceJudgment(item.eventId(), relevance, item.reason()));
                }
            }
            return List.copyOf(normalized.values());
        }
        if (evalCase.expectedEventIds() == null) {
            return List.of();
        }
        return evalCase.expectedEventIds().stream()
                .filter(id -> id != null)
                .distinct()
                .map(id -> new RetrievalDtos.RelevanceJudgment(id, 3, "Legacy expected event"))
                .toList();
    }

    private static double idealDcg(List<RetrievalDtos.RelevanceJudgment> judgments, int topK) {
        List<Integer> idealGrades = judgments.stream()
                .map(RetrievalDtos.RelevanceJudgment::relevance)
                .sorted(Comparator.reverseOrder())
                .limit(topK)
                .toList();
        double result = 0;
        for (int index = 0; index < idealGrades.size(); index++) {
            result += discountedGain(idealGrades.get(index), index + 1);
        }
        return result;
    }

    /** Graded gain 2^rel - 1, discounted by log2(rank + 1). */
    private static double discountedGain(int relevance, int rank) {
        double gain = Math.pow(2, relevance) - 1;
        return gain / (Math.log(rank + 1) / Math.log(2));
    }

    private List<String> matchedTerms(RetrievalDtos.CandidateTrace candidate, List<String> expectedTerms) {
        String text = String.join(" ",
                nullToEmpty(candidate.title()),
                nullToEmpty(candidate.organizationName()),
                nullToEmpty(candidate.category()),
                nullToEmpty(candidate.location()),
                candidate.evidence() == null ? "" : String.join(" ", candidate.evidence())
        ).toLowerCase(Locale.ROOT);
        return expectedTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .filter(term -> text.contains(term.toLowerCase(Locale.ROOT)))
                .distinct()
                .toList();
    }

    private double hitRate(List<RetrievalDtos.EvalCaseResult> results) {
        return rate(results.stream().filter(RetrievalDtos.EvalCaseResult::hit).count(), results.size());
    }

    private double average(List<RetrievalDtos.EvalCaseResult> results, ToDoubleFunction<RetrievalDtos.EvalCaseResult> metric) {
        return round(results.stream().mapToDouble(metric).average().orElse(0));
    }

    private String winner(double ndcgDelta, double reciprocalRankDelta) {
        if (ndcgDelta > WIN_THRESHOLD) {
            return "current";
        }
        if (ndcgDelta < -WIN_THRESHOLD) {
            return "baseline";
        }
        if (reciprocalRankDelta > WIN_THRESHOLD) {
            return "current";
        }
        if (reciprocalRankDelta < -WIN_THRESHOLD) {
            return "baseline";
        }
        return "tie";
    }

    private static double rate(long part, long whole) {
        return whole == 0 ? 0 : round((double) part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
