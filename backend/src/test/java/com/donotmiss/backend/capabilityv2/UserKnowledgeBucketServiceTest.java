package com.donotmiss.backend.capabilityv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserKnowledgeBucketServiceTest {
    @Mock
    private CapabilityBucketRepository bucketRepository;

    @Test
    void loadsEveryActiveKnowledgeBucketBelongingToTheCurrentUser() {
        CapabilityBucketEntity java = knowledgeBucket(7L, "student-1", "Java", "[\"java\"]");
        CapabilityBucketEntity webProgramming = knowledgeBucket(8L, "student-1", "Web 编程", "[\"web\",\"HTTP\"]");
        when(bucketRepository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                CapabilityBucketType.KNOWLEDGE,
                "student-1",
                CapabilityBucketStatus.ACTIVE
        )).thenReturn(List.of(java, webProgramming));

        List<CapabilityV2Dtos.BucketCandidate> buckets = service().loadForUser(" student-1 ");

        assertEquals(List.of("Java", "Web 编程"), buckets.stream()
                .map(CapabilityV2Dtos.BucketCandidate::displayName)
                .toList());
        assertTrue(buckets.stream().allMatch(bucket -> bucket.bucketType() == CapabilityBucketType.KNOWLEDGE));
    }

    @Test
    void returnsNoBucketsWithoutAUserId() {
        assertTrue(service().loadForUser("  ").isEmpty());
        verifyNoInteractions(bucketRepository);
    }

    private UserKnowledgeBucketService service() {
        return new UserKnowledgeBucketService(bucketRepository, new ObjectMapper());
    }

    private CapabilityBucketEntity knowledgeBucket(Long id, String ownerUserId, String displayName, String aliasesJson) {
        CapabilityBucketEntity bucket = new CapabilityBucketEntity();
        setId(bucket, id);
        bucket.setBucketType(CapabilityBucketType.KNOWLEDGE);
        bucket.setOwnerUserId(ownerUserId);
        bucket.setNormalizedName(UserKnowledgeBucketService.normalize(displayName));
        bucket.setDisplayName(displayName);
        bucket.setDescription("个人长期累计的知识与技术标签");
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
