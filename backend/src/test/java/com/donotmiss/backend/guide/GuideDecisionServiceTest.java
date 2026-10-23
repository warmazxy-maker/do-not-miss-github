package com.donotmiss.backend.guide;

import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.memory.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replays the conversation from PROBLEM_LOG 066: a complete first request that mentions a place and
 * "最好" used to be forced into a clarifying question by a keyword guard, twice in a row.
 */
@ExtendWith(MockitoExtension.class)
class GuideDecisionServiceTest {
    private static final String FIRST = "我在学 Spring Boot 和 Redis，下周想找一个能动手写代码、有工程师点评的线下活动，最好在大阪";
    private static final String ANSWER = "偏 Spring Boot / Redis 实战项目，最好现场写代码，有人帮我看代码";

    @Mock
    private OpenAiCompatibleLlmClient llmClient;
    @Mock
    private UserMemoryService userMemoryService;
    @Mock
    private AgentRunService agentRunService;
    @Mock
    private AgentTraceArtifactService traceArtifactService;

    @Test
    void completeFirstRequestWithPlaceAndPreferenceIsSearchedNotClarified() {
        modelSays("CALL_RAG_NEW", "大阪线下 Spring Boot/Redis 动手实战活动");

        GuideDtos.GuideDecision decision = service().decide("demo-student", FIRST, emptySession());

        assertEquals(GuideAction.CALL_RAG_NEW, decision.action());
        assertTrue(decision.shouldCallRag());
    }

    @Test
    void modelMayStillClarifyOnce() {
        modelSays("ASK_CLARIFY", "");

        GuideDtos.GuideDecision decision = service().decide("demo-student", "想参加点活动", emptySession());

        assertEquals(GuideAction.ASK_CLARIFY, decision.action());
    }

    @Test
    void secondClarifyInARowBecomesSearchWithBothMessages() {
        modelSays("ASK_CLARIFY", "");
        GuideDtos.GuideSessionView afterClarify = session("ASK_CLARIFY", List.of(
                new GuideDtos.GuideMessage("user", FIRST, Instant.now()),
                new GuideDtos.GuideMessage("assistant", "你更偏实战项目还是通用后端？", Instant.now())));

        GuideDtos.GuideDecision decision = service().decide("demo-student", ANSWER, afterClarify);

        assertEquals(GuideAction.CALL_RAG_NEW, decision.action());
        assertTrue(decision.ragNeed().contains("大阪"), decision.ragNeed());
        assertTrue(decision.ragNeed().contains("现场写代码"), decision.ragNeed());
    }

    @Test
    void refineWithoutPreviousGoalBecomesNewSearch() {
        modelSays("CALL_RAG_REFINE", "");

        GuideDtos.GuideDecision decision = service().decide("demo-student", ANSWER, emptySession());

        assertEquals(GuideAction.CALL_RAG_NEW, decision.action());
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceKeepsModelDecisionAndMarksGuardOverride() {
        modelSays("ASK_CLARIFY", "");
        GuideDtos.GuideSessionView afterClarify = session("ASK_CLARIFY",
                List.of(new GuideDtos.GuideMessage("user", FIRST, Instant.now())));

        service().decide("demo-student", ANSWER, afterClarify);

        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(traceArtifactService).record(any(), eq(AgentStepName.GUIDE_ACTION_DECISION),
                eq("GUIDE_ACTION_DECISION"), content.capture(), anyString());
        Map<String, Object> artifact = (Map<String, Object>) content.getValue();
        assertEquals(GuideAction.ASK_CLARIFY, ((GuideDtos.GuideDecision) artifact.get("modelDecision")).action());
        assertEquals(GuideAction.CALL_RAG_NEW, ((GuideDtos.GuideDecision) artifact.get("finalDecision")).action());
        assertEquals(true, artifact.get("overriddenByGuard"));
    }

    @Test
    void ruleFallbackSearchesACompleteRequestWhenModelIsUnavailable() {
        when(llmClient.chatForJson(anyString(), anyString(), eq(GuideDtos.GuideActionModelResponse.class)))
                .thenReturn(Optional.empty());

        GuideDtos.GuideDecision decision = service().decide("demo-student", FIRST, emptySession());

        assertEquals(GuideAction.CALL_RAG_NEW, decision.action());
    }

    private void modelSays(String action, String ragNeed) {
        when(llmClient.chatForJson(anyString(), anyString(), eq(GuideDtos.GuideActionModelResponse.class)))
                .thenReturn(Optional.of(new GuideDtos.GuideActionModelResponse(
                        action, 0.8, "model", "", ragNeed, "", List.of(), List.of())));
    }

    private GuideDecisionService service() {
        return new GuideDecisionService(llmClient, userMemoryService, agentRunService, traceArtifactService);
    }

    private static GuideDtos.GuideSessionView emptySession() {
        return session(null, List.of());
    }

    private static GuideDtos.GuideSessionView session(String lastAction, List<GuideDtos.GuideMessage> messages) {
        return new GuideDtos.GuideSessionView("s-1", "", "", lastAction, "", messages,
                List.of(), List.of(), List.of(), Instant.now(), Instant.now());
    }
}
