package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunctionBucketCandidateServiceHybridTest {
    private static final CapabilityBucketEntity MEDICAL = bucket(1L, "医学影像", "X光、CT、MRI、超声、影像判读基础");
    private static final CapabilityBucketEntity EMBEDDED = bucket(2L, "嵌入式系统开发", "MCU、RTOS、驱动开发、外设控制");
    private static final CapabilityBucketEntity BACKEND = bucket(3L, "后端开发", "服务端编程、接口设计、消息队列");

    private final CapabilityBucketRepository repository = mock(CapabilityBucketRepository.class);
    private final FunctionBucketSemanticIndex semanticIndex = mock(FunctionBucketSemanticIndex.class);

    @Test
    void theTopCandidatesComeFromMeaningNotFromSharedCharacters() {
        Map<Long, Double> similarities = withFillers(Map.of(1L, 0.01, 2L, 0.71, 3L, 0.33));
        stub(similarities);

        List<FunctionBucketCandidateService.FunctionCandidateMatch> result =
                service().retrieve(evidence("负责 STM32 主控程序编写，实现 PID 循迹与超声波避障。"));

        assertEquals("嵌入式系统开发", result.getFirst().candidate().displayName());
        assertEquals(FunctionBucketCandidateService.RecallSource.SEMANTIC, result.getFirst().source());
        assertEquals(0.71, result.getFirst().semanticSimilarity());
        assertFalse(names(result).contains("医学影像"), "\"超声\" is a shared word, not a mention of the job function");
        assertEquals(10, result.size());
    }

    @Test
    void aJobFunctionNamedVerbatimIsAddedEvenWithALowSemanticRank() {
        Map<Long, Double> similarities = withFillers(Map.of(1L, 0.01, 2L, 0.71, 3L, 0.02));
        stub(similarities);

        List<FunctionBucketCandidateService.FunctionCandidateMatch> result =
                service().retrieve(evidence("实习期间负责后端开发，编写对账任务。"));

        FunctionBucketCandidateService.FunctionCandidateMatch backend = result.stream()
                .filter(match -> match.candidate().bucketId() == 3L).findFirst().orElseThrow();
        assertEquals(FunctionBucketCandidateService.RecallSource.EXPLICIT_NAME, backend.source());
        assertEquals(11, result.size(), "10 semantic candidates plus the explicitly named one");
    }

    @Test
    void withoutEmbeddingsTheKeywordRankingIsReturnedUnchanged() {
        stub(null);

        List<FunctionBucketCandidateService.FunctionCandidateMatch> result =
                service().retrieve(evidence("负责 STM32 主控程序编写，实现 PID 循迹与超声波避障。"));

        assertEquals(List.of("医学影像"), names(result));
        assertEquals(FunctionBucketCandidateService.RecallSource.KEYWORD, result.getFirst().source());
        assertFalse(result.getFirst().semantic());
    }

    private FunctionBucketCandidateService service() {
        return new FunctionBucketCandidateService(repository, new ObjectMapper(), semanticIndex);
    }

    /** About 150 unrelated buckets, so ranks behave as they would among the 372 real ones. */
    private Map<Long, Double> withFillers(Map<Long, Double> core) {
        Map<Long, Double> similarities = new HashMap<>(core);
        for (long id = 100; id < 250; id++) {
            similarities.put(id, 0.20 + id / 10000.0);
        }
        return similarities;
    }

    private void stub(Map<Long, Double> similarities) {
        List<CapabilityBucketEntity> buckets = new ArrayList<>(List.of(MEDICAL, EMBEDDED, BACKEND));
        if (similarities != null) {
            for (long id = 100; id < 250; id++) {
                buckets.add(bucket(id, "无关职能" + id, "甲乙丙丁"));
            }
        }
        when(repository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                CapabilityBucketType.FUNCTION, CapabilityBucketEntity.GLOBAL_OWNER, CapabilityBucketStatus.ACTIVE))
                .thenReturn(buckets);
        when(semanticIndex.similarities(any(), any())).thenReturn(Optional.ofNullable(similarities));
    }

    private List<String> names(List<FunctionBucketCandidateService.FunctionCandidateMatch> matches) {
        return matches.stream().map(match -> match.candidate().displayName()).toList();
    }

    private static CapabilityBucketEntity bucket(Long id, String name, String description) {
        CapabilityBucketEntity bucket = new CapabilityBucketEntity();
        ReflectionTestUtils.setField(bucket, "id", id);
        bucket.setBucketType(CapabilityBucketType.FUNCTION);
        bucket.setOwnerUserId(CapabilityBucketEntity.GLOBAL_OWNER);
        bucket.setNormalizedName(name);
        bucket.setDisplayName(name);
        bucket.setDescription(description);
        bucket.setAliasesJson("[]");
        bucket.setStatus(CapabilityBucketStatus.ACTIVE);
        return bucket;
    }

    private CapabilityEvidenceInput evidence(String description) {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2", "golden:T", "golden:T", CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION,
                "u", "T", AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                "测试经历", description, "", "{}", "{}", List.of(), Instant.now(), true, "ok", "a".repeat(64));
    }
}
