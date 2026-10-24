package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.memory.UserMemoryService;
import com.donotmiss.backend.mcp.McpDtos;
import com.donotmiss.backend.mcp.McpToolContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RetrievalTraceService {
    private static final int DEFAULT_TRACE_LIMIT = 20;

    private final UserMemoryService userMemoryService;
    private final McpToolContextService mcpToolContextService;
    private final QueryRewriteService queryRewriteService;
    private final HybridEventRetrievalService hybridEventRetrievalService;

    public RetrievalTraceService(UserMemoryService userMemoryService,
                                 McpToolContextService mcpToolContextService,
                                 QueryRewriteService queryRewriteService,
                                 HybridEventRetrievalService hybridEventRetrievalService) {
        this.userMemoryService = userMemoryService;
        this.mcpToolContextService = mcpToolContextService;
        this.queryRewriteService = queryRewriteService;
        this.hybridEventRetrievalService = hybridEventRetrievalService;
    }

    @Transactional(readOnly = true)
    public RetrievalDtos.TraceResponse trace(String userId, RetrievalDtos.TraceRequest request) {
        UserMemoryDtos.Profile memory = userMemoryService.profile(userId);
        McpDtos.ToolContextResponse toolContext = mcpToolContextService.resolve(request.toolContext());
        RetrievalDtos.QueryRewrite rewrite = queryRewriteService.rewrite(request.need(), memory);
        int limit = normalizeLimit(request.limit());

        List<RetrievedEvent> retrievedEvents = hybridEventRetrievalService.retrieve(
                rewrite.rewrittenQuery(),
                request.category(),
                request.benefitType(),
                request.location(),
                memory,
                limit,
                toolContext
        );

        return new RetrievalDtos.TraceResponse(
                request.need(),
                rewrite,
                memory,
                toolContext,
                toCandidateTraces(retrievedEvents)
        );
    }

    @Transactional(readOnly = true)
    public RetrievalDtos.TraceResponse traceBaseline(String userId, RetrievalDtos.TraceRequest request) {
        UserMemoryDtos.Profile memory = userMemoryService.profile(userId);
        McpDtos.ToolContextResponse toolContext = mcpToolContextService.resolve(request.toolContext());
        RetrievalDtos.QueryRewrite rewrite = queryRewriteService.rewrite(request.need(), memory);
        int limit = normalizeLimit(request.limit());

        List<RetrievedEvent> retrievedEvents = hybridEventRetrievalService.retrieveBaseline(
                rewrite.rewrittenQuery(),
                request.category(),
                request.benefitType(),
                request.location(),
                memory,
                limit,
                toolContext
        );

        return new RetrievalDtos.TraceResponse(
                request.need(),
                rewrite,
                memory,
                toolContext,
                toCandidateTraces(retrievedEvents)
        );
    }

    /**
     * Retrieval with every input pinned by the caller: profile, clock and corpus. Evaluation uses
     * this so that results depend only on the evaluation set, not on who runs it or when.
     */
    @Transactional(readOnly = true)
    public CorpusTrace traceOver(RetrievalDtos.TraceRequest request,
                                 UserMemoryDtos.Profile memory,
                                 McpDtos.ToolContextResponse toolContext,
                                 EventCorpus corpus,
                                 boolean baseline) {
        RetrievalDtos.QueryRewrite rewrite = queryRewriteService.rewrite(request.need(), memory);
        int limit = normalizeLimit(request.limit());
        List<RetrievedEvent> retrievedEvents = baseline
                ? hybridEventRetrievalService.retrieveBaseline(rewrite.rewrittenQuery(), request.category(),
                request.benefitType(), request.location(), memory, limit, toolContext, corpus)
                : hybridEventRetrievalService.retrieve(rewrite.rewrittenQuery(), request.category(),
                request.benefitType(), request.location(), memory, limit, toolContext, corpus);
        return new CorpusTrace(
                new RetrievalDtos.TraceResponse(request.need(), rewrite, memory, toolContext, toCandidateTraces(retrievedEvents)),
                retrievedEvents);
    }

    /** The trace view plus the retrieved entities, which ranking needs. */
    public record CorpusTrace(RetrievalDtos.TraceResponse response, List<RetrievedEvent> events) {
    }

    @Transactional(readOnly = true)
    public RetrievalDtos.TraceResponse trace(String userId, AiDtos.EventRecommendationRequest request, int limit) {
        return trace(userId, new RetrievalDtos.TraceRequest(
                request.need(),
                request.category(),
                request.benefitType(),
                request.location(),
                limit,
                request.toolContext()
        ));
    }

    private List<RetrievalDtos.CandidateTrace> toCandidateTraces(List<RetrievedEvent> retrievedEvents) {
        AtomicInteger rank = new AtomicInteger(1);
        return retrievedEvents.stream()
                .map(item -> toCandidateTrace(rank.getAndIncrement(), item))
                .toList();
    }

    private RetrievalDtos.CandidateTrace toCandidateTrace(int rank, RetrievedEvent item) {
        EventEntity event = item.event();
        return new RetrievalDtos.CandidateTrace(
                rank,
                event.getId(),
                event.getTitle(),
                event.getOrganizationName(),
                event.getCategory().label(),
                event.getLocation(),
                item.keywordScore(),
                item.bm25Score(),
                item.semanticScore(),
                item.intentScore(),
                item.memoryScore(),
                item.metadataScore(),
                item.recallScore(),
                item.finalScore(),
                item.evidence()
        );
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_TRACE_LIMIT;
        }
        return Math.min(Math.max(limit, 1), 100);
    }
}
