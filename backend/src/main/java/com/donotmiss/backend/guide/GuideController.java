package com.donotmiss.backend.guide;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.mcp.McpDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/guide")
public class GuideController {
    private final GuideSessionService guideSessionService;
    private final GuideDecisionService guideDecisionService;
    private final GuideRagToolService guideRagToolService;
    private final GuideConversationService guideConversationService;
    private final CurrentUser currentUser;

    public GuideController(GuideSessionService guideSessionService,
                           GuideDecisionService guideDecisionService,
                           GuideRagToolService guideRagToolService,
                           GuideConversationService guideConversationService,
                           CurrentUser currentUser) {
        this.guideSessionService = guideSessionService;
        this.guideDecisionService = guideDecisionService;
        this.guideRagToolService = guideRagToolService;
        this.guideConversationService = guideConversationService;
        this.currentUser = currentUser;
    }

    @PostMapping("/actions")
    public GuideDtos.GuideDecisionResponse decideAction(@Valid @RequestBody GuideDtos.GuideChatRequest request,
                                                        HttpServletRequest servletRequest) {
        String userId = currentUser.id(servletRequest);
        GuideSessionEntity session = guideSessionService.loadOrCreate(userId, request.sessionId());
        GuideDtos.GuideDecision decision = guideDecisionService.decide(
                userId,
                request.message(),
                guideSessionService.view(session)
        );
        session = guideSessionService.appendMessage(session, "user", request.message());
        session = guideSessionService.saveDecision(session, decision);
        return new GuideDtos.GuideDecisionResponse(
                session.getSessionId(),
                decision,
                guideSessionService.view(session)
        );
    }

    @PostMapping("/chat")
    public GuideDtos.GuideChatResponse chat(@Valid @RequestBody GuideDtos.GuideChatRequest request,
                                            HttpServletRequest servletRequest) {
        String userId = currentUser.id(servletRequest);
        GuideSessionEntity session = guideSessionService.loadOrCreate(userId, request.sessionId());
        GuideDtos.GuideDecision decision = guideDecisionService.decide(
                userId,
                request.message(),
                guideSessionService.view(session)
        );

        session = guideSessionService.appendMessage(session, "user", request.message());
        session = guideSessionService.saveDecision(session, decision);

        boolean usedRag = false;
        String reply;
        List<AiDtos.RecommendedEvent> recommendations = List.of();
        McpDtos.ToolContextResponse toolContext = null;
        Long recommendationRunId = null;

        if (decision.shouldCallRag()) {
            GuideRagToolService.ToolResult toolResult = guideRagToolService.recommendEvents(
                    userId,
                    decision,
                    request.toolContext()
            );
            usedRag = true;
            reply = toolResult.message();
            recommendations = toolResult.recommendations() == null ? List.of() : toolResult.recommendations();
            toolContext = toolResult.toolContext();
            recommendationRunId = toolResult.runId();
            session = guideSessionService.saveRagSnapshot(
                    session,
                    decision.action(),
                    decision.currentGoal(),
                    decision.ragNeed(),
                    recommendations
            );
        } else {
            reply = guideConversationService.replyWithoutRag(
                    userId,
                    request.message(),
                    decision,
                    guideSessionService.view(session)
            );
        }

        session = guideSessionService.appendMessage(session, "assistant", reply);
        session = guideConversationService.compressIfNeeded(userId, session);
        return new GuideDtos.GuideChatResponse(
                session.getSessionId(),
                reply,
                decision.action(),
                usedRag,
                recommendations,
                guideSessionService.view(session),
                toolContext,
                recommendationRunId
        );
    }
}
