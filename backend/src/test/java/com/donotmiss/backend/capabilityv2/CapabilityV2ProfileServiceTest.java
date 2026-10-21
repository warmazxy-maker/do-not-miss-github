package com.donotmiss.backend.capabilityv2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityV2ProfileServiceTest {
    @Mock
    private UserCapabilityStateRepository stateRepository;

    @Mock
    private CapabilityBucketRepository bucketRepository;

    @Test
    void separatesActiveKnowledgeAndFunctionBucketsAndKeepsScoreOrder() {
        UserCapabilityStateEntity javaState = state(1L, "student-1", 10L, "5.0", "1.5", "0.70", "FOUNDATION", 2);
        UserCapabilityStateEntity functionState = state(2L, "student-1", 20L, "4.0", "2.0", "0.40", "DEVELOPING", 3);
        UserCapabilityStateEntity disabledState = state(3L, "student-1", 30L, "9.0", "4.0", "0.20", "ADVANCED", 5);

        when(stateRepository.findByUserIdOrderByAbilityScoreDescUpdatedAtDesc("student-1"))
                .thenReturn(List.of(disabledState, functionState, javaState));
        when(bucketRepository.findAllById(List.of(30L, 20L, 10L)))
                .thenReturn(List.of(
                        bucket(10L, CapabilityBucketType.KNOWLEDGE, "Java", CapabilityBucketStatus.ACTIVE),
                        bucket(20L, CapabilityBucketType.FUNCTION, "后端开发", CapabilityBucketStatus.ACTIVE),
                        bucket(30L, CapabilityBucketType.KNOWLEDGE, "旧标签", CapabilityBucketStatus.DISABLED)
                ));

        CapabilityV2ProfileService.CapabilityProfile profile = service().profileForUser(" student-1 ");

        assertEquals(1, profile.knowledgeBuckets().size());
        assertEquals("Java", profile.knowledgeBuckets().getFirst().displayName());
        assertEquals(1, profile.functionBuckets().size());
        assertEquals("后端开发", profile.functionBuckets().getFirst().displayName());
        assertEquals(5, profile.totalEvidenceCount());
        assertEquals("DEVELOPING", profile.functionBuckets().getFirst().rankName());
        assertFalse(profile.functionBuckets().getFirst().promotionPending());
    }

    @Test
    void flagsBucketWhoseScoreEarnsAGatedRankItDoesNotHoldYet() {
        UserCapabilityStateEntity held = state(1L, "student-1", 10L, "40.0", "55.0", "0.30", "FOUNDATION", 6);
        when(stateRepository.findByUserIdOrderByAbilityScoreDescUpdatedAtDesc("student-1")).thenReturn(List.of(held));
        when(bucketRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(bucket(10L, CapabilityBucketType.KNOWLEDGE, "Java", CapabilityBucketStatus.ACTIVE)));

        CapabilityV2ProfileService.CapabilitySummary summary = service().profileForUser("student-1")
                .knowledgeBuckets().getFirst();

        assertTrue(summary.promotionPending());
        assertEquals("PROFICIENT", summary.pendingRank(), "the reminder names the rank the score already earns");
    }

    @Test
    void returnsEmptyProfileWithoutStates() {
        when(stateRepository.findByUserIdOrderByAbilityScoreDescUpdatedAtDesc("new-user"))
                .thenReturn(List.of());

        CapabilityV2ProfileService.CapabilityProfile profile = service().profileForUser("new-user");

        assertTrue(profile.knowledgeBuckets().isEmpty());
        assertTrue(profile.functionBuckets().isEmpty());
        assertEquals(0, profile.totalEvidenceCount());
    }

    private CapabilityV2ProfileService service() {
        return new CapabilityV2ProfileService(stateRepository, bucketRepository,
                new RankPromotionPolicy(new com.donotmiss.backend.abilityscore.AbilityScoreCalculator()));
    }

    private UserCapabilityStateEntity state(Long id, String userId, Long bucketId, String experience,
                                             String score, String uncertainty, String rank, int evidenceCount) {
        UserCapabilityStateEntity state = new UserCapabilityStateEntity();
        ReflectionTestUtils.setField(state, "id", id);
        state.setUserId(userId);
        state.setBucketId(bucketId);
        state.setExperienceScore(new BigDecimal(experience));
        state.setAbilityScore(new BigDecimal(score));
        state.setAbilityUncertainty(new BigDecimal(uncertainty));
        state.setRankName(rank);
        state.setEvidenceCount(evidenceCount);
        ReflectionTestUtils.setField(state, "updatedAt", Instant.parse("2026-08-14T08:00:00Z"));
        return state;
    }

    private CapabilityBucketEntity bucket(Long id, CapabilityBucketType type, String name,
                                           CapabilityBucketStatus status) {
        CapabilityBucketEntity bucket = new CapabilityBucketEntity();
        ReflectionTestUtils.setField(bucket, "id", id);
        bucket.setBucketType(type);
        bucket.setOwnerUserId(type == CapabilityBucketType.FUNCTION
                ? CapabilityBucketEntity.GLOBAL_OWNER
                : "student-1");
        bucket.setNormalizedName(name.toLowerCase());
        bucket.setDisplayName(name);
        bucket.setDescription(name + "说明");
        bucket.setStatus(status);
        return bucket;
    }
}
