package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.abilityscore.AbilityFairnessPolicy;
import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityV2ScoringServiceTest {
    private final CapabilityV2ScoringService service = new CapabilityV2ScoringService(
            new AbilityScoreCalculator(),
            new AbilityFairnessPolicy(),
            null,
            null,
            null,
            null,
            new RankPromotionPolicy(new AbilityScoreCalculator()),
            new ObjectMapper()
    );

    @Test
    void givesDifferentBucketGainsFromDifferentRelevanceAndKeepsPrimaryFunction() {
        CapabilityV2ScoringService.ScoringPreview preview = service.preview(request(
                List.of(
                        assignment(1L, CapabilityBucketType.KNOWLEDGE, "Java", "0.92", "0.90", CapabilityEvidenceRole.NONE),
                        assignment(2L, CapabilityBucketType.KNOWLEDGE, "MySQL", "0.42", "0.88", CapabilityEvidenceRole.NONE)
                ),
                List.of(assignment(3L, CapabilityBucketType.FUNCTION, "后端开发", "0.55", "0.80", CapabilityEvidenceRole.PRIMARY))
        ), new BigDecimal("0.5000"));

        assertEquals(3, preview.bucketResults().size());
        assertEquals("后端开发", preview.bucketResults().getFirst().bucketName());
        BigDecimal javaGain = resultFor(preview, "Java").scoreOutput().verifiedExperienceGain();
        BigDecimal mysqlGain = resultFor(preview, "MySQL").scoreOutput().verifiedExperienceGain();
        assertTrue(javaGain.compareTo(mysqlGain) > 0);
    }

    @Test
    void capsOneEvidenceAtThreeBucketsAndNormalizesTheSharedRelevanceBudget() {
        CapabilityV2ScoringService.ScoringPreview preview = service.preview(request(
                List.of(
                        assignment(1L, CapabilityBucketType.KNOWLEDGE, "Java", "0.95", "0.90", CapabilityEvidenceRole.NONE),
                        assignment(2L, CapabilityBucketType.KNOWLEDGE, "MySQL", "0.90", "0.90", CapabilityEvidenceRole.NONE),
                        assignment(3L, CapabilityBucketType.KNOWLEDGE, "Redis", "0.85", "0.90", CapabilityEvidenceRole.NONE),
                        assignment(4L, CapabilityBucketType.KNOWLEDGE, "消息队列", "0.80", "0.90", CapabilityEvidenceRole.NONE)
                ),
                List.of()
        ), new BigDecimal("0.5000"));

        assertEquals(3, preview.bucketResults().size());
        assertEquals(1, preview.skippedAssignments());
        BigDecimal adjustedTotal = preview.bucketResults().stream()
                .map(CapabilityV2ScoringService.PreviewBucketResult::adjustedRelevance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(adjustedTotal.compareTo(new BigDecimal("2.0001")) <= 0);
    }

    @Test
    void rejectsEvidenceThatTheUnifiedAdapterMarkedIneligible() {
        CapabilityEvidenceInput ineligible = new CapabilityEvidenceInput(
                "capability-evidence-v2", "coach-log:1", "key-1", CapabilityEvidenceSourceKind.COACH_LOG,
                "student-1", "1", AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD.name(),
                AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD.credibilityPrior(),
                "今日复盘", "", "", "{}", "{}", List.of(), Instant.parse("2026-08-14T00:00:00Z"),
                false, "Coach 日志不能直接计分", "a".repeat(64)
        );

        CapabilityV2ScoringService.ScoreRequest request = new CapabilityV2ScoringService.ScoreRequest(
                ineligible, null, signals(), new BigDecimal("0.70"), BigDecimal.ONE,
                List.of(assignment(1L, CapabilityBucketType.KNOWLEDGE, "Java", "0.90", "0.90", CapabilityEvidenceRole.NONE)),
                List.of(),
                null
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.preview(request, new BigDecimal("0.5000")));
    }

    private CapabilityV2ScoringService.PreviewBucketResult resultFor(
            CapabilityV2ScoringService.ScoringPreview preview, String bucketName
    ) {
        return preview.bucketResults().stream()
                .filter(item -> bucketName.equals(item.bucketName()))
                .findFirst()
                .orElseThrow();
    }

    private CapabilityV2ScoringService.ScoreRequest request(
            List<CapabilityV2Dtos.BucketAssignment> knowledge,
            List<CapabilityV2Dtos.BucketAssignment> functions
    ) {
        return new CapabilityV2ScoringService.ScoreRequest(
                evidence(), 99L, signals(), new BigDecimal("0.82"), new BigDecimal("0.95"), knowledge, functions, null
        );
    }

    private CapabilityV2ScoringService.ScoreSignals signals() {
        return new CapabilityV2ScoringService.ScoreSignals(
                new BigDecimal("42"), new BigDecimal("0.90"),
                new BigDecimal("0.85"), new BigDecimal("0.90"),
                new BigDecimal("0.82"), new BigDecimal("0.88"),
                new BigDecimal("0.86"), List.of("完成了可运行模块"), List.of()
        );
    }

    private CapabilityEvidenceInput evidence() {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2", "achievement-record:1", "key-1",
                CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION, "student-1", "101",
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                "Java 服务端实践", "完成接口、数据库访问与测试。", "独立完成接口联调。", "{}", "{}",
                List.of("完成接口设计", "完成数据库访问"), Instant.parse("2026-08-14T00:00:00Z"),
                true, "已完成活动", "a".repeat(64)
        );
    }

    private CapabilityV2Dtos.BucketAssignment assignment(
            Long id,
            CapabilityBucketType type,
            String name,
            String relevance,
            String confidence,
            CapabilityEvidenceRole role
    ) {
        return new CapabilityV2Dtos.BucketAssignment(
                id, type, name, new BigDecimal(relevance), new BigDecimal(confidence), role,
                "完成 " + name + " 相关交付", List.of("完成 " + name + " 相关实践"), List.of()
        );
    }
}
