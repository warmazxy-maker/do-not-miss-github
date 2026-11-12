package com.donotmiss.backend.ops;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.AgentRegistry;
import com.donotmiss.backend.agentkit.AgentSpec;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolRegistry;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.donotmiss.backend.ratelimit.RateLimited;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * F0 ops endpoints: the tool and agent registries (read-only, F0 decision 1), and the playground
 * where an admin runs an agent against a chosen user's real data to see which tools it picks.
 */
@RestController
@RequestMapping("/api/ops/agent-tools")
public class OpsAgentToolController {
    private final OpsAccessGuard guard;
    private final CurrentUser currentUser;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final AgentLoop agentLoop;
    private final UserRepository userRepository;

    public OpsAgentToolController(OpsAccessGuard guard, CurrentUser currentUser, ToolRegistry toolRegistry,
                                  AgentRegistry agentRegistry, AgentLoop agentLoop, UserRepository userRepository) {
        this.guard = guard;
        this.currentUser = currentUser;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.agentLoop = agentLoop;
        this.userRepository = userRepository;
    }

    public record ToolRow(String name, int version, String title, String description, Map<String, String> risk,
                          String permission, String rateLimitTier, long timeoutSeconds, boolean enabled,
                          List<String> usedBy, JsonNode parameters) {
    }

    public record AgentRow(String name, String title, String promptVersion, int maxSteps, String rateLimitTier,
                           List<String> tools) {
    }

    public record Catalog(List<ToolRow> tools, List<AgentRow> agents) {
    }

    public record PlaygroundRequest(
            @NotBlank String agent,
            @NotBlank String username,
            @NotBlank @Size(max = 2000) String message) {
    }

    public record PlaygroundResponse(String agent, String username, String role, AgentLoop.Reply reply) {
    }

    @GetMapping
    public Catalog catalog(HttpServletRequest request) {
        guard.require(request);
        List<ToolRow> tools = toolRegistry.all().stream().map(tool -> {
            ToolDefinition d = tool.definition();
            return new ToolRow(d.name(), d.version(), d.title(), d.description(), d.risk().dimensions(),
                    d.permission().name(), d.rateLimitTier() == null ? null : d.rateLimitTier().name(),
                    d.timeout().toSeconds(), toolRegistry.isEnabled(d.name()),
                    agentRegistry.agentsUsing(d.name()), d.parameters());
        }).toList();
        List<AgentRow> agents = agentRegistry.all().stream()
                .map(agent -> new AgentRow(agent.name(), agent.title(), agent.promptVersion(), agent.maxSteps(),
                        agent.rateLimitTier().name(), agent.toolNames()))
                .toList();
        return new Catalog(tools, agents);
    }

    /**
     * Tools see the chosen user's data; the run is recorded under the admin, so the playground does
     * not show up in that user's own history.
     */
    @PostMapping("/playground")
    @RequirePermission(value = Permission.OPS_ACTION, action = "ops.agentPlayground")
    @RateLimited(RateLimitTier.AGENT_TURN)
    public PlaygroundResponse playground(HttpServletRequest request, @Valid @RequestBody PlaygroundRequest body) {
        guard.require(request);
        AgentSpec agent = agentRegistry.find(body.agent().trim())
                .orElseThrow(() -> ApiException.notFound("没有这个 Agent：" + body.agent()));
        UserEntity target = userRepository.findByUsername(body.username().trim())
                .orElseThrow(() -> ApiException.notFound("没有这个用户：" + body.username()));
        AgentLoop.Reply reply = agentLoop.run(agent, target.getPublicUserId(), currentUser.id(request),
                AgentRunType.AGENT_PLAYGROUND, List.of(), body.message().trim());
        return new PlaygroundResponse(agent.name(), target.getUsername(), target.getRole(), reply);
    }
}
