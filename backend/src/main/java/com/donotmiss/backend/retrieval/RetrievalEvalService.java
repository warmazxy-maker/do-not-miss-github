package com.donotmiss.backend.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        List<RetrievalDtos.EvalCase> cases = loadCases();
        List<RetrievalDtos.EvalCaseResult> results = cases.stream()
                .map(evalCase -> evaluateCase(userId, evalCase, false))
                .toList();

        int hitCount = (int) results.stream().filter(RetrievalDtos.EvalCaseResult::hit).count();
        double averagePrecision = results.stream()
                .mapToDouble(RetrievalDtos.EvalCaseResult::precisionAtK)
                .average()
                .orElse(0);
        RetrievalDtos.EvalSummary summary = new RetrievalDtos.EvalSummary(
                results.size(),
                hitCount,
                results.isEmpty() ? 0 : round((double) hitCount / results.size()),
                round(averagePrecision)
        );
        return new RetrievalDtos.EvalResponse(summary, results);
    }

    @Transactional(readOnly = true)
    public RetrievalDtos.EvalAbResponse evaluateAb(String userId) {
        List<RetrievalDtos.EvalCase> cases = loadCases();
        List<RetrievalDtos.EvalAbCaseResult> results = cases.stream()
                .map(evalCase -> evaluateAbCase(userId, evalCase))
                .toList();

        double currentHitRate = hitRate(results.stream()
                .map(RetrievalDtos.EvalAbCaseResult::current)
                .toList());
        double baselineHitRate = hitRate(results.stream()
                .map(RetrievalDtos.EvalAbCaseResult::baseline)
                .toList());
        double currentMrr = meanReciprocalRank(results.stream()
                .map(RetrievalDtos.EvalAbCaseResult::current)
                .toList());
        double baselineMrr = meanReciprocalRank(results.stream()
                .map(RetrievalDtos.EvalAbCaseResult::baseline)
                .toList());
        int improvedCount = (int) results.stream().filter(item -> "current".equals(item.winner())).count();
        int regressedCount = (int) results.stream().filter(item -> "baseline".equals(item.winner())).count();
        int tiedCount = (int) results.stream().filter(item -> "tie".equals(item.winner())).count();

        RetrievalDtos.EvalAbSummary summary = new RetrievalDtos.EvalAbSummary(
                results.size(),
                currentHitRate,
                baselineHitRate,
                currentMrr,
                baselineMrr,
                improvedCount,
                regressedCount,
                tiedCount
        );
        return new RetrievalDtos.EvalAbResponse(summary, results);
    }

    private RetrievalDtos.EvalAbCaseResult evaluateAbCase(String userId, RetrievalDtos.EvalCase evalCase) {
        RetrievalDtos.EvalCaseResult current = evaluateCase(userId, evalCase, false);
        RetrievalDtos.EvalCaseResult baseline = evaluateCase(userId, evalCase, true);
        double delta = round(current.reciprocalRank() - baseline.reciprocalRank());
        String winner = winner(delta);
        return new RetrievalDtos.EvalAbCaseResult(
                evalCase.id(),
                evalCase.need(),
                current.topK(),
                current,
                baseline,
                winner,
                delta,
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

        List<Long> expectedEventIds = evalCase.expectedEventIds() == null ? List.of() : evalCase.expectedEventIds();
        List<String> expectedTerms = evalCase.expectedTerms() == null ? List.of() : evalCase.expectedTerms();
        List<Long> matchedEventIds = new ArrayList<>();
        List<String> matchedTerms = new ArrayList<>();
        int relevantCount = 0;
        Integer bestExpectedRank = null;

        for (RetrievalDtos.CandidateTrace candidate : topCandidates) {
            List<String> candidateMatches = matchedTerms(candidate, expectedTerms);
            boolean idMatched = candidate.eventId() != null && expectedEventIds.contains(candidate.eventId());
            boolean termMatched = expectedEventIds.isEmpty() && !candidateMatches.isEmpty();
            boolean relevant = idMatched || termMatched;
            if (relevant) {
                relevantCount += 1;
                if (candidate.eventId() != null && !matchedEventIds.contains(candidate.eventId())) {
                    matchedEventIds.add(candidate.eventId());
                }
                if (bestExpectedRank == null) {
                    bestExpectedRank = candidate.rank();
                }
            }
            candidateMatches.forEach(term -> {
                if (!matchedTerms.contains(term)) {
                    matchedTerms.add(term);
                }
            });
        }

        double reciprocalRank = bestExpectedRank == null ? 0 : round(1.0 / bestExpectedRank);
        return new RetrievalDtos.EvalCaseResult(
                evalCase.id(),
                evalCase.need(),
                topK,
                bestExpectedRank != null,
                round((double) relevantCount / topK),
                expectedEventIds,
                matchedEventIds,
                bestExpectedRank,
                reciprocalRank,
                expectedTerms,
                matchedTerms,
                topCandidates,
                trace.queryRewrite(),
                evalCase.note()
        );
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
        long hits = results.stream().filter(RetrievalDtos.EvalCaseResult::hit).count();
        return round((double) hits / results.size());
    }

    private double meanReciprocalRank(List<RetrievalDtos.EvalCaseResult> results) {
        return round(results.stream()
                .mapToDouble(RetrievalDtos.EvalCaseResult::reciprocalRank)
                .average()
                .orElse(0));
    }

    private String winner(double reciprocalRankDelta) {
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
