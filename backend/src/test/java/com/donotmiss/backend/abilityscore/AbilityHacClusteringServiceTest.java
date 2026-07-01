package com.donotmiss.backend.abilityscore;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbilityHacClusteringServiceTest {
    private final AbilityFeatureVectorizer vectorizer = new AbilityFeatureVectorizer();
    private final AbilityDynamicAnchorRegistryService anchorRegistry = mock(AbilityDynamicAnchorRegistryService.class);
    private final AbilityHacClusteringService service = new AbilityHacClusteringService(
            mock(UserAbilityStateRepository.class),
            vectorizer,
            anchorRegistry,
            0.62
    );

    AbilityHacClusteringServiceTest() {
        when(anchorRegistry.approvedAnchors()).thenReturn(List.of());
        when(anchorRegistry.minMembers()).thenReturn(3);
        when(anchorRegistry.promoteStableCluster(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Optional.empty());
    }

    @Test
    void clustersJavaDimensionsButKeepsJapaneseSeparate() {
        List<AbilityClusterModels.AbilityClusterResponse> result = service.cluster(List.of(
                state(1L, "Java 编程基础", "java-programming-foundation", 21, 35, 0.35),
                state(2L, "Java 后端开发能力", "java-backend-development", 34, 48, 0.25),
                state(3L, "Java 编程实践", "java-programming-practice", 27, 42, 0.20),
                state(4L, "日语口语交流", "japanese-speaking", 18, 31, 0.40)
        ));

        assertThat(result).hasSize(2);
        AbilityClusterModels.AbilityClusterResponse javaCluster = result.stream()
                .filter(cluster -> cluster.name().equals("Java"))
                .findFirst()
                .orElseThrow();
        assertThat(javaCluster.memberCount()).isEqualTo(3);
        assertThat(javaCluster.members())
                .extracting(AbilityClusterModels.AbilityClusterMemberResponse::dimension)
                .containsExactlyInAnyOrder("Java 编程基础", "Java 后端开发能力", "Java 编程实践");
        assertThat(result.stream().filter(cluster -> cluster.name().equals("日语")).findFirst())
                .isPresent();
    }

    @Test
    void doesNotMergeJavaAndJavascriptBySubstring() {
        List<AbilityClusterModels.AbilityClusterResponse> result = service.cluster(List.of(
                state(1L, "Java 后端开发", "java-backend", 20, 40, 0.3),
                state(2L, "JavaScript 前端开发", "javascript-frontend", 20, 40, 0.3)
        ));

        assertThat(result).hasSize(2);
    }

    @Test
    void mapsFrameworkAndAgentToolsToTheirParentDomains() {
        List<AbilityClusterModels.AbilityClusterResponse> result = service.cluster(List.of(
                state(1L, "Java 后端开发", "java-backend", 20, 40, 0.3),
                state(2L, "Spring Boot 工程实践", "spring-boot-engineering", 20, 42, 0.3),
                state(3L, "Agent 系统设计", "agent-system-design", 20, 38, 0.3),
                state(4L, "LangGraph 工程能力", "langgraph-engineering", 20, 36, 0.3),
                state(5L, "AI 工程基础", "ai-engineering-foundation", 20, 34, 0.3)
        ));

        assertThat(result).hasSize(3);
        assertThat(result).anySatisfy(cluster -> {
            assertThat(cluster.name()).isEqualTo("Java");
            assertThat(cluster.memberCount()).isEqualTo(2);
        });
        assertThat(result).anySatisfy(cluster -> {
            assertThat(cluster.name()).isEqualTo("Agent");
            assertThat(cluster.memberCount()).isEqualTo(2);
        });
        assertThat(result).anySatisfy(cluster -> {
            assertThat(cluster.name()).isEqualTo("AI Engineering");
            assertThat(cluster.memberCount()).isEqualTo(1);
        });
    }

    @Test
    void aggregatesScoreWithConfidenceAndEvidenceWeights() {
        AbilityClusterModels.AbilityClusterResponse cluster = service.cluster(List.of(
                state(1L, "Java 编程基础", "java-foundation", 10, 20, 0.8),
                state(2L, "Java 后端开发", "java-backend", 100, 60, 0.1)
        )).getFirst();

        assertThat(cluster.abilityScore()).isGreaterThan(new BigDecimal("45"));
        assertThat(cluster.experienceScore()).isEqualByComparingTo("103.5000");
        assertThat(cluster.algorithmVersion()).isEqualTo("hac-average-v1.1-dynamic-anchor");
    }

    private UserAbilityStateEntity state(Long id,
                                         String dimension,
                                         String normalized,
                                         double experience,
                                         double ability,
                                         double uncertainty) {
        UserAbilityStateEntity state = new UserAbilityStateEntity();
        setId(state, id);
        state.setUserId("user-1");
        state.setDimensionName(dimension);
        state.setNormalizedDimension(normalized);
        state.setExperienceScore(BigDecimal.valueOf(experience));
        state.setAbilityScore(BigDecimal.valueOf(ability));
        state.setAbilityUncertainty(BigDecimal.valueOf(uncertainty));
        state.setRankName("FOUNDATION");
        return state;
    }

    private void setId(UserAbilityStateEntity state, Long id) {
        try {
            var field = UserAbilityStateEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(state, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
