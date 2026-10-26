package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
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

    public RetrievalController(RetrievalTraceService retrievalTraceService,
                               RetrievalEvalService retrievalEvalService,
                               EventSearchIndexService eventSearchIndexService,
                               CurrentUser currentUser) {
        this.retrievalTraceService = retrievalTraceService;
        this.retrievalEvalService = retrievalEvalService;
        this.eventSearchIndexService = eventSearchIndexService;
        this.currentUser = currentUser;
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
    @RequirePermission(Permission.OPS_ACTION)
    public RetrievalDtos.EvalResponse evaluate() {
        return retrievalEvalService.evaluate();
    }

    @GetMapping("/evaluation/ab")
    @RequirePermission(Permission.OPS_ACTION)
    public RetrievalDtos.EvalAbResponse evaluateAb() {
        return retrievalEvalService.evaluateAb();
    }

    @GetMapping("/evaluation/end-to-end")
    @RequirePermission(Permission.OPS_ACTION)
    public RetrievalDtos.EndToEndResponse evaluateEndToEnd() {
        return retrievalEvalService.evaluateEndToEnd();
    }

    /** Rebuilds the live index from MySQL. Was unauthenticated until B0; now needs OPS_ACTION. */
    @PostMapping("/reindex")
    @RequirePermission(value = Permission.OPS_ACTION, action = "search.reindex")
    public RetrievalDtos.ReindexResponse reindex() {
        int indexedCount = eventSearchIndexService.reindexAll();
        boolean enabled = eventSearchIndexService.isEnabled();
        return new RetrievalDtos.ReindexResponse(
                enabled,
                indexedCount,
                enabled ? "Search index rebuilt." : "Search engine is disabled. Set SEARCH_ENABLED=true to enable it."
        );
    }
}
