package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.memory.UserMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PROBLEM_LOG 075: "不怎么想参加线上活动" was rewritten to location = 线上 and filtered the right event out. */
class QueryRewriteLocationTest {
    private static final String NEED = "平日晚上能参加的线下活动，想练日语口语，不怎么想参加线上活动";

    private final OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
    private final QueryRewriteService service = new QueryRewriteService(llm, mock(UserMemoryService.class));

    QueryRewriteLocationTest() {
        when(llm.isEnabled()).thenReturn(true);
        when(llm.modeLabel()).thenReturn("test-model");
    }

    private void modelSays(String location, String benefit, List<String> constraints, String action) {
        when(llm.chatForJson(anyString(), anyString(), eq(RetrievalDtos.QueryRewriteModelResponse.class)))
                .thenReturn(Optional.of(new RetrievalDtos.QueryRewriteModelResponse(
                        "练日语口语", "intermediate", List.of("日语", "口语"), List.of("日语口语"), List.of(),
                        location, benefit, constraints,
                        new RetrievalDtos.ContextDecision(action, "REFINE", 0.9, "test"))));
    }

    @Test
    void aNegatedLocationWordNoLongerBecomesTheLocation() {
        modelSays("", "unknown", List.of("不要线上活动"), "CLEAR");

        RetrievalDtos.QueryRewrite rewrite = service.rewrite(NEED, null, null);

        assertThat(rewrite.preferredLocation()).isEmpty();
        assertThat(rewrite.constraints()).contains("不要线上活动").doesNotContain("线上可参与");
        // The ranker reads the negation; the BM25 text must not, or it would boost online events.
        assertThat(rewrite.rewrittenQuery()).doesNotContain("不要线上活动").doesNotContain("线上可参与");
        assertThat(rewrite.evidence()).noneMatch(item -> item.startsWith("rule: online preference"));
        // "练" made the keyword rules say "skill"; the model said unknown and the model wins.
        assertThat(rewrite.benefitPreference()).isEqualTo("unknown");
    }

    @Test
    void theModelsOwnLocationIsKeptWhenTheStudentSaidIt() {
        modelSays("线下", "skill", List.of(), "CLEAR");

        RetrievalDtos.QueryRewrite rewrite = service.rewrite(NEED, null, null);

        assertThat(rewrite.preferredLocation()).isEqualTo("线下");
        assertThat(rewrite.benefitPreference()).isEqualTo("skill");
    }

    @Test
    void aLocationTheStudentNeverMentionedIsDropped() {
        modelSays("东京", "unknown", List.of(), "CLEAR");

        assertThat(service.rewrite(NEED, null, null).preferredLocation()).isEmpty();
    }

    private static RetrievalDtos.SearchSessionContext previousOnline() {
        return new RetrievalDtos.SearchSessionContext("想找线上的活动", "线上 日语", "练日语", "unknown",
                List.of("日语"), List.of(), List.of(), "线上", "unknown", List.of("线上可参与"),
                List.of("想找线上的活动"), Instant.now());
    }

    @Test
    void aContinuedSessionDoesNotInheritTheLocation() {
        modelSays("", "unknown", List.of(), "MERGE");
        assertThat(service.rewrite("想练日语口语", null, previousOnline()).preferredLocation()).isEmpty();

        modelSays("", "unknown", List.of(), "KEEP");
        assertThat(service.rewrite("还有吗", null, previousOnline()).preferredLocation()).isEmpty();
    }

    @Test
    void withoutAModelTheKeywordRulesStillGiveAFallback() {
        when(llm.isEnabled()).thenReturn(false);

        RetrievalDtos.QueryRewrite rewrite = service.rewrite("想找线上能参加的日语活动", null, null);

        assertThat(rewrite.preferredLocation()).isEqualTo("线上");
        assertThat(rewrite.mode()).isEqualTo("rule");
    }
}
