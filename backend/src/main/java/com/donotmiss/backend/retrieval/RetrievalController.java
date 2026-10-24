package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.ops.OpsAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/retrieval")
public class RetrievalController {
    private final RetrievalTraceService retrievalTraceService;
    private final RetrievalEvalService retrievalEvalService;
    private final EventSearchIndexService eventSearchIndexService;
    private final CurrentUser currentUser;
    private final OpsAccessGuard opsAccessGuard;

    public RetrievalController(RetrievalTraceService retrievalTraceService,
                               RetrievalEvalService retrievalEvalService,
                               EventSearchIndexService eventSearchIndexService,
                               CurrentUser currentUser,
                               OpsAccessGuard opsAccessGuard) {
        this.retrievalTraceService = retrievalTraceService;
        this.retrievalEvalService = retrievalEvalService;
        this.eventSearchIndexService = eventSearchIndexService;
        this.currentUser = currentUser;
        this.opsAccessGuard = opsAccessGuard;
    }

    @PostMapping("/trace")
    public RetrievalDtos.TraceResponse trace(@Valid @RequestBody RetrievalDtos.TraceRequest request,
                                             HttpServletRequest servletRequest) {
        return retrievalTraceService.trace(currentUser.id(servletRequest), request);
    }

    /**
     * Evaluation calls the model once or twice per case and builds an index, so it is an operator
     * action: same access rule as the operations console.
     */
    @GetMapping("/evaluation")
    public RetrievalDtos.EvalResponse evaluate(HttpServletRequest servletRequest) {
        opsAccessGuard.require(servletRequest);
        return retrievalEvalService.evaluate();
    }

    @GetMapping("/evaluation/ab")
    public RetrievalDtos.EvalAbResponse evaluateAb(HttpServletRequest servletRequest) {
        opsAccessGuard.require(servletRequest);
        return retrievalEvalService.evaluateAb();
    }

    @GetMapping("/evaluation/end-to-end")
    public RetrievalDtos.EndToEndResponse evaluateEndToEnd(HttpServletRequest servletRequest) {
        opsAccessGuard.require(servletRequest);
        return retrievalEvalService.evaluateEndToEnd();
    }

    /** Rebuilds the live index from MySQL. Was unauthenticated; now an operator action. */
    @PostMapping("/reindex")
    public RetrievalDtos.ReindexResponse reindex(HttpServletRequest servletRequest) {
        opsAccessGuard.require(servletRequest);
        int indexedCount = eventSearchIndexService.reindexAll();
        boolean enabled = eventSearchIndexService.isEnabled();
        return new RetrievalDtos.ReindexResponse(
                enabled,
                indexedCount,
                enabled ? "Search index rebuilt." : "Search engine is disabled. Set SEARCH_ENABLED=true to enable it."
        );
    }
}
