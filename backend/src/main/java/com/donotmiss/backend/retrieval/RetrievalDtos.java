package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.mcp.McpDtos;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public class RetrievalDtos {
    public record QueryRewrite(
            String mode,
            String originalQuery,
            String rewrittenQuery,
            String goal,
            String level,
            List<String> intentTags,
            List<String> skills,
            List<String> preferredCategories,
            String preferredLocation,
            String benefitPreference,
            List<String> constraints,
            ContextDecision contextDecision,
            List<String> evidence
    ) {
    }

    public record ContextDecision(
            String action,
            String relation,
            double confidence,
            String reason
    ) {
    }

    public record SearchSessionContext(
            String originalQuery,
            String rewrittenQuery,
            String goal,
            String level,
            List<String> intentTags,
            List<String> skills,
            List<String> preferredCategories,
            String preferredLocation,
            String benefitPreference,
            List<String> constraints,
            List<String> recentUserMessages,
            Instant updatedAt
    ) {
        public static SearchSessionContext from(QueryRewrite query) {
            return new SearchSessionContext(
                    query.originalQuery(),
                    query.rewrittenQuery(),
                    query.goal(),
                    query.level(),
                    query.intentTags(),
                    query.skills(),
                    query.preferredCategories(),
                    query.preferredLocation(),
                    query.benefitPreference(),
                    query.constraints(),
                    List.of(query.originalQuery()),
                    Instant.now()
            );
        }
    }

    public record QueryRewriteModelResponse(
            String goal,
            String level,
            List<String> intentTags,
            List<String> skills,
            List<String> preferredCategories,
            String preferredLocation,
            String benefitPreference,
            List<String> constraints,
            ContextDecision contextDecision
    ) {
    }

    public record RerankModelItem(
            Long eventId,
            String reason
    ) {
    }

    public record RerankModelResponse(
            List<RerankModelItem> items
    ) {
    }

    public record TraceRequest(
            @NotBlank String need,
            String category,
            String benefitType,
            String location,
            Integer limit,
            McpDtos.ToolContextRequest toolContext
    ) {
    }

    public record CandidateTrace(
            int rank,
            Long eventId,
            String title,
            String organizationName,
            String category,
            String location,
            double keywordScore,
            double bm25Score,
            double semanticScore,
            double intentScore,
            double memoryScore,
            double metadataScore,
            double recallScore,
            double finalScore,
            List<String> evidence
    ) {
    }

    public record TraceResponse(
            String need,
            QueryRewrite queryRewrite,
            UserMemoryDtos.Profile memory,
            McpDtos.ToolContextResponse toolContext,
            List<CandidateTrace> candidates
    ) {
    }

    public record EvalCase(
            String id,
            String need,
            String category,
            String benefitType,
            String location,
            List<RelevanceJudgment> judgments,
            List<Long> expectedEventIds,
            List<String> expectedTerms,
            Integer topK,
            String note,
            // Events that must NOT be in the top K for this need, e.g. a student marked them not relevant.
            List<Long> rejectedEventIds,
            // Where the case came from, e.g. "manual" or "feedback:candidate-12".
            String source
    ) {
    }

    public record RelevanceJudgment(
            Long eventId,
            int relevance,
            String reason
    ) {
    }

    public record EvalCaseResult(
            String id,
            String need,
            int topK,
            boolean hit,
            double precisionAtK,
            double recallAtK,
            double ndcgAtK,
            List<RelevanceJudgment> judgments,
            List<Long> matchedEventIds,
            Integer bestRelevantRank,
            Integer bestAnswerRank,
            double reciprocalRank,
            List<String> expectedTerms,
            List<String> matchedTerms,
            List<CandidateTrace> topCandidates,
            QueryRewrite queryRewrite,
            String note,
            List<Long> rejectedEventIds,
            List<Long> rejectedInTopK
    ) {
    }

    /**
     * Which evaluation produced a result. Results are only comparable when evalSetVersion,
     * corpusFingerprint and mode match.
     */
    public record EvalRunInfo(
            String mode,
            String evalSetVersion,
            String labelStatus,
            String corpusVersion,
            String corpusFingerprint,
            int corpusSize,
            String asOf,
            String modelMode,
            Instant ranAt
    ) {
    }

    public record EvalSummary(
            int caseCount,
            int hitCount,
            double hitRate,
            double averagePrecisionAtK,
            double averageRecallAtK,
            double averageNdcgAtK,
            double meanReciprocalRank,
            double zeroResultRate,
            double expiredReturnRate,
            int rejectedCaseCount,
            double rejectedHitRate
    ) {
    }

    public record EvalResponse(
            EvalRunInfo run,
            EvalSummary summary,
            List<EvalCaseResult> results
    ) {
    }

    /** One case of the end-to-end evaluation: retrieval, then the same LLM ranking as a live request. */
    public record EndToEndCaseResult(
            String id,
            String need,
            int topK,
            boolean hit,
            double ndcgAtK,
            double reciprocalRank,
            Integer bestAnswerRank,
            List<Long> recommendedEventIds,
            List<Long> modelEventIds,
            List<Long> invalidModelEventIds,
            boolean ruleFallback,
            double retrievalNdcgAtK,
            List<RelevanceJudgment> judgments,
            String note,
            List<Long> rejectedEventIds,
            List<Long> rejectedInTopK
    ) {
    }

    public record EndToEndSummary(
            int caseCount,
            double hitRate,
            double averageNdcgAtK,
            double meanReciprocalRank,
            double averageRetrievalNdcgAtK,
            double zeroResultRate,
            double expiredReturnRate,
            double invalidModelIdRate,
            double ruleFallbackRate,
            int rejectedCaseCount,
            double rejectedHitRate
    ) {
    }

    public record EndToEndResponse(
            EvalRunInfo run,
            EndToEndSummary summary,
            List<EndToEndCaseResult> results
    ) {
    }

    public record EvalAbCaseResult(
            String id,
            String need,
            int topK,
            EvalCaseResult current,
            EvalCaseResult baseline,
            String winner,
            double ndcgDelta,
            double reciprocalRankDelta,
            String note
    ) {
    }

    public record EvalAbSummary(
            int caseCount,
            double currentHitRate,
            double baselineHitRate,
            double currentAverageRecallAtK,
            double baselineAverageRecallAtK,
            double currentAverageNdcgAtK,
            double baselineAverageNdcgAtK,
            double currentMeanReciprocalRank,
            double baselineMeanReciprocalRank,
            int improvedCount,
            int regressedCount,
            int tiedCount
    ) {
    }

    public record EvalAbResponse(
            EvalRunInfo run,
            EvalAbSummary summary,
            List<EvalAbCaseResult> results
    ) {
    }

    public record ReindexResponse(
            boolean searchEnabled,
            int indexedCount,
            String message
    ) {
    }
}
