package com.donotmiss.backend.capabilityv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage 07: loads a user's entire personal knowledge-label list for the normalizer.
 *
 * <p>Knowledge labels are deliberately user-scoped. They are small in number and may use
 * different wording across users (for example, “网络编程” versus “Web 编程”), so sending the
 * complete list to the LLM is clearer than pretending it needs a global RAG lookup.</p>
 */
@Service
public class UserKnowledgeBucketService {
    private final CapabilityBucketRepository bucketRepository;
    private final ObjectMapper objectMapper;

    public UserKnowledgeBucketService(CapabilityBucketRepository bucketRepository,
                                      ObjectMapper objectMapper) {
        this.bucketRepository = bucketRepository;
        this.objectMapper = objectMapper;
    }

    public List<CapabilityV2Dtos.BucketCandidate> loadForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return bucketRepository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                        CapabilityBucketType.KNOWLEDGE,
                        userId.trim(),
                        CapabilityBucketStatus.ACTIVE
                ).stream()
                .map(this::toBucket)
                .toList();
    }

    public CapabilityV2Dtos.BucketCandidate toBucket(CapabilityBucketEntity bucket) {
        return new CapabilityV2Dtos.BucketCandidate(
                bucket.getId(),
                bucket.getBucketType(),
                bucket.getDisplayName(),
                bucket.getDomainName(),
                bucket.getGroupName(),
                bucket.getDescription(),
                aliases(bucket.getAliasesJson())
        );
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                normalized.append(Character.toLowerCase(current));
            }
        }
        return normalized.toString();
    }

    private List<String> aliases(String aliasesJson) {
        if (aliasesJson == null || aliasesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(aliasesJson, new TypeReference<>() { });
            return parsed == null ? List.of() : parsed.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .limit(20)
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }
}
