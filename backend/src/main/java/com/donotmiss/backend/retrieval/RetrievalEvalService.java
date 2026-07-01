package com.donotmiss.backend.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RetrievalEvalService {
    private static final int DEFAULT_TOP_K = 5;
    private static final double WIN_THRESHOLD = 0.001;

    private final ObjectMapper objectMapper;
    private final RetrievalTraceService retrievalTraceService;

    public RetrievalEvalService(ObjectMapper objectMapper, RetrievalTraceService retrievalTraceService) {
        this.objectMapper = objectMapper;
        this.retrievalTraceService = retrievalTraceService;
    }

    @Transactional(readOnly = true)
    public RetrievalDtos.EvalResponse evaluate(String userId) {
        List<RetrievalDtos.EvalCaseResult> results = loadCases().stream()
                .map(evalCase -> evaluateCase(userId, evalCase, false))
                .toList();

        int hitCount = (int) results.stream().filter(RetrievalDtos.EvalCaseResult::hit).count();
        RetrievalDtos.EvalSummary summary = new RetrievalDtos.EvalSummary(
                results.size(),
                hitCount,
                results.isEmpty() ? 0 : round((double) hitCount / results.size()),
                average(results, RetrievalDtos.EvalCaseResult::precisionAtK),
                average(results, RetrievalDtos.EvalCaseResult::recallAtK),
                average(results, RetrievalDtos.EvalCaseResult::ndcgAtK),
                average(results, RetrievalDtos.EvalCaseResult::reciprocalRank)
        );
        return new RetrievalDtos.EvalResponse(summary, results);
    }

    @Transactional(readOnly = true)
    public RetrievalDtos.EvalAbResponse evaluateAb(String userId) {
        List<RetrievalDtos.EvalAbCaseResult> results = loadCases().stream()
                .map(evalCase -> evaluateAbCase(userId, evalCase))
                .toList();
        List<RetrievalDtos.EvalCaseResult> currentResults = results.stream()
                .map(RetrievalDtos.EvalAbCaseResult::current)
                .toList();
        List<RetrievalDtos.EvalCaseResult> baselineResults = results.stream()
                .map(RetrievalDtos.EvalAbCaseResult::baseline)
                .toList();

        RetrievalDtos.EvalAbSummary summary = new RetrievalDtos.EvalAbSummary(
                results.size(),
                hitRate(currentResults),
                hitRate(baselineResults),
                average(currentResults, RetrievalDtos.EvalCaseResult::recallAtK),
                average(baselineResults, RetrievalDtos.EvalCaseResult::recallAtK),
                average(currentResults, RetrievalDtos.EvalCaseResult::ndcgAtK),
                average(baselineResults, RetrievalDtos.EvalCaseResult::ndcgAtK),
                average(currentResults, RetrievalDtos.EvalCaseResult::reciprocalRank),
                average(baselineResults, RetrievalDtos.EvalCaseResult::reciprocalRank),
                (int) results.stream().filter(item -> "current".equals(item.winner())).count(),
                (int) results.stream().filter(item -> "baseline".equals(item.winner())).count(),
                (int) results.stream().filter(item -> "tie".equals(item.winner())).count()
        );
        return new RetrievalDtos.EvalAbResponse(summary, results);
    }

    private RetrievalDtos.EvalAbCaseResult evaluateAbCase(String userId, RetrievalDtos.EvalCase evalCase) {
        RetrievalDtos.EvalCaseResult current = evaluateCase(userId, evalCase, false);
        RetrievalDtos.EvalCaseResult baseline = evaluateCase(userId, evalCase, true);
        double ndcgDelta = round(current.ndcgAtK() - baseline.ndcgAtK());
        double reciprocalRankDelta = round(current.reciprocalRank() - baseline.reciprocalRank());
        return new RetrievalDtos.EvalAbCaseResult(
                evalCase.id(),
                evalCase.need(),
                current.topK(),
                current,
                baseline,
                winner(ndcgDelta, reciprocalRankDelta),
                ndcgDelta,
                reciprocalRankDelta,
                evalCase.note()
        );
    }

    private RetrievalDtos.EvalCaseResult evaluateCase(String userId,
                                                       RetrievalDtos.EvalCase evalCase,
                                                       boolean baseline) {
        int topK = evalCase.topK() == null ? DEFAULT_TOP_K : Math.max(evalCase.topK(), 1);
        RetrievalDtos.TraceRequest request = new RetrievalDtos.TraceRequest(
                evalCase.need(),
                evalCase.category(),
                evalCase.benefitType(),
                evalCase.location(),
                topK,
                null
        );
        RetrievalDtos.TraceResponse trace = baseline
                ? retrievalTraceService.traceBaseline(userId, request)
                : retrievalTraceService.trace(userId, request);
        List<RetrievalDtos.CandidateTrace> topCandidates = trace.candidates().stream()
                .limit(topK)
                .toList();

        List<RetrievalDtos.RelevanceJudgment> judgments = judgments(evalCase);
        Map<Long, Integer> relevanceByEventId = new LinkedHashMap<>();
        judgments.forEach(item -> relevanceByEventId.merge(item.eventId(), item.relevance(), Math::max));
        List<String> expectedTerms = evalCase.expectedTerms() == null ? List.of() : evalCase.expectedTerms();
        List<Long> matchedEventIds = new ArrayList<>();
        List<String> matchedTerms = new ArrayList<>();
        Integer bestRelevantRank = null;
        Integer bestAnswerRank = null;
        int maxRelevance = judgments.stream()
                .mapToInt(RetrievalDtos.RelevanceJudgment::relevance)
                .max()
                .orElse(0);
        double dcg = 0;

        for (int index = 0; index < topCandidates.size(); index++) {
            RetrievalDtos.CandidateTrace candidate = topCandidates.get(index);
            int rank = index + 1;
            int relevance = relevanceByEventId.getOrDefault(candidate.eventId(), 0);
            if (relevance > 0) {
                matchedEventIds.add(candidate.eventId());
                if (bestRelevantRank == null) {
                    bestRelevantRank = rank;
                }
                if (bestAnswerRank == null && relevance == maxRelevance) {
                    bestAnswerRank = rank;
                }
                dcg += discountedGain(relevance, rank);
            }
            matchedTerms(candidate, expectedTerms).forEach(term -> {
                if (!matchedTerms.contains(term)) {
                    matchedTerms.add(term);
                }
            });
        }

        double idealDcg = idealDcg(judgments, topK);
        double ndcgAtK = idealDcg == 0 ? 0 : round(dcg / idealDcg);
        double precisionAtK = round((double) matchedEventIds.size() / topK);
        double recallAtK = judgments.isEmpty()
                ? 0
                : round((double) matchedEventIds.size() / judgments.size());
        double reciprocalRank = bestAnswerRank == null ? 0 : round(1.0 / bestAnswerRank);

        return new RetrievalDtos.EvalCaseResult(
                evalCase.id(),
                evalCase.need(),
                topK,
                bestRelevantRank != null,
                precisionAtK,
                recallAtK,
                ndcgAtK,
                judgments,
                List.copyOf(matchedEventIds),
                bestRelevantRank,
                bestAnswerRank,
                reciprocalRank,
                expectedTerms,
                List.copyOf(matchedTerms),
                topCandidates,
                trace.queryRewrite(),
                evalCase.note()
        );
    }

    private List<RetrievalDtos.RelevanceJudgment> judgments(RetrievalDtos.EvalCase evalCase) {
        if (evalCase.judgments() != null && !evalCase.judgments().isEmpty()) {
            Map<Long, RetrievalDtos.RelevanceJudgment> normalized = new LinkedHashMap<>();
            for (RetrievalDtos.RelevanceJudgment item : evalCase.judgments()) {
                if (item == null || item.eventId() == null || item.relevance() <= 0) {
                    continue;
                }
                int relevance = Math.min(item.relevance(), 3);
                RetrievalDtos.RelevanceJudgment existing = normalized.get(item.eventId());
                if (existing == null || relevance > existing.relevance()) {
                    normalized.put(item.eventId(), new RetrievalDtos.RelevanceJudgment(
                            item.eventId(),
                            relevance,
                            item.reason()
                    ));
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

    private double idealDcg(List<RetrievalDtos.RelevanceJudgment> judgments, int topK) {
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

    private double discountedGain(int relevance, int rank) {
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

    private List<RetrievalDtos.EvalCase> loadCases() {
        try {
            ClassPathResource resource = new ClassPathResource("retrieval-eval-set.json");
            return objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load retrieval evaluation set.", ex);
        }
    }

    private double hitRate(List<RetrievalDtos.EvalCaseResult> results) {
        if (results.isEmpty()) {
            return 0;
        }
        return round((double) results.stream().filter(RetrievalDtos.EvalCaseResult::hit).count()
                / results.size());
    }

    private double average(List<RetrievalDtos.EvalCaseResult> results,
                           java.util.function.ToDoubleFunction<RetrievalDtos.EvalCaseResult> metric) {
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

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
