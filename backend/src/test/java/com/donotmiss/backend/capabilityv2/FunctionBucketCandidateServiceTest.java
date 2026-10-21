package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionBucketCandidateServiceTest {
    @Mock
    private CapabilityBucketRepository bucketRepository;

    @Test
    void ranksBackendDevelopmentFromConcreteInterfaceDeliveryEvidence() {
        CapabilityBucketEntity backend = functionBucket(
                7L,
                "后端开发",
                "服务端编程、接口设计、业务系统、数据库访问、缓存、鉴权",
                "[\"后端开发\",\"服务端编程\",\"接口设计\",\"数据库访问\"]"
        );
        CapabilityBucketEntity communityService = functionBucket(
                8L,
                "社区公益服务",
                "志愿服务、社区活动、居民沟通",
                "[\"志愿服务\",\"社区活动\"]"
        );
        activeBuckets(backend, communityService);

        List<FunctionBucketCandidateService.FunctionCandidateMatch> result = service().retrieve(backendEvidence());

        assertFalse(result.isEmpty());
        assertEquals("后端开发", result.getFirst().candidate().displayName());
        assertTrue(result.getFirst().retrievalConfidence().doubleValue() > 0.50d);
        assertTrue(result.getFirst().matchedTerms().contains("接口设计"));
    }

    @Test
    void doesNotReturnUnrelatedFunctionsFromGenericLearningWords() {
        CapabilityBucketEntity communityService = functionBucket(
                8L,
                "社区公益服务",
                "志愿服务、社区活动、居民沟通",
                "[\"志愿服务\",\"社区活动\"]"
        );
        activeBuckets(communityService);

        assertTrue(service().retrieve(backendEvidence()).isEmpty());
    }

    private void activeBuckets(CapabilityBucketEntity... buckets) {
        when(bucketRepository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                CapabilityBucketType.FUNCTION,
                CapabilityBucketEntity.GLOBAL_OWNER,
                CapabilityBucketStatus.ACTIVE
        )).thenReturn(List.of(buckets));
    }

    private FunctionBucketCandidateService service() {
        return new FunctionBucketCandidateService(bucketRepository, new ObjectMapper());
    }

    private CapabilityEvidenceInput backendEvidence() {
        return new CapabilityEvidenceInput(
                "capability-evidence-v2",
                "achievement-record:1",
                "key-1",
                CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION,
                "student-1",
                "101",
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.name(),
                AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD.credibilityPrior(),
                "Spring Boot 后端接口交付",
                "负责服务端接口实现、MySQL 数据访问与鉴权逻辑。",
                "独立完成接口设计和接口联调。",
                "{}",
                "{}",
                List.of("完成接口设计", "实现数据库访问和鉴权"),
                Instant.parse("2026-08-14T00:00:00Z"),
                true,
                "test",
                "a".repeat(64)
        );
    }

    private CapabilityBucketEntity functionBucket(Long id, String displayName, String description, String aliasesJson) {
        CapabilityBucketEntity bucket = new CapabilityBucketEntity();
        setId(bucket, id);
        bucket.setBucketType(CapabilityBucketType.FUNCTION);
        bucket.setOwnerUserId(CapabilityBucketEntity.GLOBAL_OWNER);
        bucket.setNormalizedName(FunctionBucketCandidateService.normalize(displayName));
        bucket.setDisplayName(displayName);
        bucket.setDescription(description);
        bucket.setAliasesJson(aliasesJson);
        bucket.setSource("TEST");
        bucket.setStatus(CapabilityBucketStatus.ACTIVE);
        return bucket;
    }

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
