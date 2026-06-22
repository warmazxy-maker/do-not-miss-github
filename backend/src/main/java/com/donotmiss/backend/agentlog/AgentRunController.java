package com.donotmiss.backend.agentlog;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/agent-runs")
public class AgentRunController {
    private final AgentRunService agentRunService;
    private final CurrentUser currentUser;

    public AgentRunController(AgentRunService agentRunService, CurrentUser currentUser) {
        this.agentRunService = agentRunService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AgentLogDtos.RunSummary> recent(HttpServletRequest request) {
        return agentRunService.recent(currentUser.id(request));
    }

    @GetMapping("/{runId}")
    public AgentLogDtos.RunDetail detail(@PathVariable Long runId, HttpServletRequest request) {
        return agentRunService.detail(currentUser.id(request), runId);
    }
}
