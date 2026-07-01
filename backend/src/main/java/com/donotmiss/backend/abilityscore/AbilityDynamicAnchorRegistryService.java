package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class AbilityDynamicAnchorRegistryService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final Set<String> OVER_BROAD_NAMES = Set.of(
            "能力", "综合能力", "实践能力", "学习能力", "技术能力", "软件开发", "开发能力", "AI"
    );

    private final AbilityDynamicAnchorRepository repository;
    private final AbilityFeatureVectorizer vectorizer;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int minMembers;
    private final double autoApproveThreshold;

    public AbilityDynamicAnchorRegistryService(AbilityDynamicAnchorRepository repository,
                                               AbilityFeatureVectorizer vectorizer,
                                               OpenAiCompatibleLlmClient llmClient,
                                               ObjectMapper objectMapper,
                                               @Value("${app.ability-clustering.dynamic-anchor-enabled:true}") boolean enabled,
                                               @Value("${app.ability-clustering.dynamic-anchor-min-members:3}") int minMembers,
                                               @Value("${app.ability-clustering.dynamic-anchor-auto-approve-threshold:0.66}") double autoApproveThreshold) {
        this.repository = repository;
        this.vectorizer = vectorizer;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.minMembers = Math.max(2, minMembers);
        this.autoApproveThreshold = Math.max(0.0, Math.min(1.0, autoApproveThreshold));
    }

    @Transactional(readOnly = true)
    public List<AbilityFeatureVectorizer.AnchorDefinition> approvedAnchors() {
        if (!enabled) {
            return List.of();
        }
        return repository.findByStatusOrderByConfidenceDescUpdatedAtDesc("APPROVED").stream()
                .map(this::toDefinition)
                .flatMap(Optional::stream)
                .toList();
    }

    @Transactional
    public Optional<AbilityFeatureVectorizer.AnchorDefinition> promoteStableCluster(
            List<UserAbilityStateEntity> members,
            double averageSimilarity
    ) {
        if (!enabled || members == null || members.size() < minMembers || averageSimilarity < 0.50) {
            return Optional.empty();
        }

        List<String> dimensions = members.stream()
                .map(UserAbilityStateEntity::getDimensionName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (dimensions.size() < minMembers) {
            return Optional.empty();
        }

        AnchorNaming naming = llmNaming(dimensions)
                .filter(candidate -> !isOverBroad(candidate.displayName()))
                .orElseGet(() -> fallbackNaming(dimensions));

        String normalizedKey = vectorizer.normalizeKey(naming.displayName());
        if (normalizedKey.isBlank()) {
            normalizedKey = vectorizer.normalizeKey(dimensions.getFirst());
        }
        if (normalizedKey.isBlank()) {
            return Optional.empty();
        }

        BigDecimal confidence = BigDecimal.valueOf(Math.max(0.5500, Math.min(0.9500, averageSimilarity)))
                .setScale(4, RoundingMode.HALF_UP);
        String status = averageSimilarity >= autoApproveThreshold ? "APPROVED" : "CANDIDATE";

        AbilityDynamicAnchorEntity entity = repository.findByNormalizedKey(normalizedKey)
                .orElseGet(AbilityDynamicAnchorEntity::new);
        entity.setNormalizedKey(normalizedKey);
        entity.setDisplayName(trimTo(naming.displayName(), 100));
        entity.setDescription(trimTo(naming.description(), 600));
        entity.setAliasesJson(toJson(merge(naming.aliases(), dimensions)));
        entity.setMemberDimensionsJson(toJson(dimensions));
        entity.setSource(naming.source());
        entity.setStatus(status);
        entity.setConfidence(confidence);
        entity.setSupportCount(Math.max(entity.getSupportCount(), dimensions.size()));

        AbilityDynamicAnchorEntity saved = repository.save(entity);
        return toDefinition(saved);
    }

    public int minMembers() {
        return minMembers;
    }

    private Optional<AbilityFeatureVectorizer.AnchorDefinition> toDefinition(AbilityDynamicAnchorEntity entity) {
        List<String> aliases = readStringList(entity.getAliasesJson());
        if (aliases.isEmpty()) {
            aliases = readStringList(entity.getMemberDimensionsJson());
        }
        if (aliases.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AbilityFeatureVectorizer.AnchorDefinition(
                entity.getNormalizedKey(),
                entity.getDisplayName(),
                aliases,
                entity.getSource(),
                entity.getStatus()
        ));
    }

    private Optional<AnchorNaming> llmNaming(List<String> dimensions) {
        String systemPrompt = """
                You name compact parent ability anchors for a student ability map.
                Return strict JSON only.
                The name must be specific, not broad. Prefer 2-6 Chinese characters or a common English technical domain.
                Do not return broad names such as 软件开发, 技术能力, 学习能力, 实践能力, AI.
                """;
        String userPrompt = """
                These ability labels were clustered by a local HAC algorithm:
                %s

                Return JSON:
                {
                  "displayName": "short parent ability name",
                  "description": "why these labels belong together",
                  "aliases": ["short aliases or member domain words"],
                  "confidence": 0.0
                }
                """.formatted(String.join("\n", dimensions));

        return llmClient.chatForJson(systemPrompt, userPrompt, AnchorNamingResponse.class)
                .filter(response -> response.displayName() != null && !response.displayName().isBlank())
                .map(response -> new AnchorNaming(
                        response.displayName().trim(),
                        response.description() == null ? "" : response.description().trim(),
                        response.aliases() == null ? List.of() : response.aliases(),
                        "LLM_SUGGESTED"
                ));
    }

    private AnchorNaming fallbackNaming(List<String> dimensions) {
        String candidate = shortestCommonName(dimensions);
        return new AnchorNaming(
                candidate,
                "Local HAC discovered a stable unlabeled ability cluster.",
                dimensions,
                "AUTO_DISCOVERED"
        );
    }

    private String shortestCommonName(List<String> dimensions) {
        return dimensions.stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted((left, right) -> Integer.compare(left.length(), right.length()))
                .findFirst()
                .orElse("新能力方向");
    }

    private boolean isOverBroad(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return OVER_BROAD_NAMES.stream().anyMatch(item -> item.equalsIgnoreCase(name) || lower.equals(item.toLowerCase(Locale.ROOT)));
    }

    private List<String> merge(List<String> left, List<String> right) {
        Set<String> result = new LinkedHashSet<>();
        if (left != null) {
            left.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(result::add);
        }
        if (right != null) {
            right.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(result::add);
        }
        return new ArrayList<>(result);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String trimTo(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private record AnchorNaming(
            String displayName,
            String description,
            List<String> aliases,
            String source
    ) {
    }

    private record AnchorNamingResponse(
            String displayName,
            String description,
            List<String> aliases,
            Double confidence
    ) {
    }
}
