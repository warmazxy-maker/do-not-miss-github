package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.common.CurrentUser;
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

    @GetMapping("/evaluation")
    public RetrievalDtos.EvalResponse evaluate(HttpServletRequest servletRequest) {
        return retrievalEvalService.evaluate(currentUser.id(servletRequest));
    }

    @GetMapping("/evaluation/ab")
    public RetrievalDtos.EvalAbResponse evaluateAb(HttpServletRequest servletRequest) {
        return retrievalEvalService.evaluateAb(currentUser.id(servletRequest));
    }

    @PostMapping("/reindex")
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
