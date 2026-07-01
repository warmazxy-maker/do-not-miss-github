package com.donotmiss.backend.agentlog;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/bad-cases")
public class AgentBadCaseController {
    private final AgentBadCaseService badCaseService;
    private final CurrentUser currentUser;

    public AgentBadCaseController(AgentBadCaseService badCaseService, CurrentUser currentUser) {
        this.badCaseService = badCaseService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public AgentBadCaseDtos.Response create(@RequestBody AgentBadCaseDtos.CreateRequest request,
                                            HttpServletRequest servletRequest) {
        return badCaseService.create(currentUser.id(servletRequest), request);
    }

    @GetMapping
    public List<AgentBadCaseDtos.Response> recent(HttpServletRequest request) {
        return badCaseService.recent(currentUser.id(request));
    }
}
