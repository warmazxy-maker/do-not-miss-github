package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import com.donotmiss.backend.abilityscore.UserAbilityStateEntity;
import com.donotmiss.backend.abilityscore.UserAbilityStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stage 13 read-only bridge from the legacy fine-grained ability states to V2 coarse buckets.
 *
 * <p>The old system has no V2 classification audit for historic records. This preview therefore
 * never calls an LLM and never creates a V2 bucket/state. It uses explicit technology rules when
 * possible, retains unknown labels for manual review, and exposes the aggregation strategy so a
 * later formal migration can be approved separately.</p>
 */
@Service
public class LegacyAbilityMigrationPreviewService {
    private static final BigDecimal OTHER_EXPERIENCE_WEIGHT = new BigDecimal("0.3500");
    private static final BigDecimal MIN_EVIDENCE_WEIGHT = new BigDecimal("1.0000");
    private static final BigDecimal EXACT_RULE_CONFIDENCE = BigDecimal.ONE;
    private static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.5800");
    private static final int MAX_ITEMS = 80;

    private final UserAbilityStateRepository legacyStateRepository;
    private final AbilityScoreCalculator scoreCalculator;

    public LegacyAbilityMigrationPreviewService(UserAbilityStateRepository legacyStateRepository,
                                                AbilityScoreCalculator scoreCalculator) {
        this.legacyStateRepository = legacyStateRepository;
        this.scoreCalculator = scoreCalculator;
    }

    /**
     * Produces a plan only. No V2 table and no legacy table is written in this method.
     */
    @Transactional(readOnly = true)
    public MigrationPreview previewForUser(String rawUserId) {
        String userId = requireUserId(rawUserId);
        List<UserAbilityStateEntity> states = legacyStateRepository
                .findByUserIdOrderByAbilityScoreDesc(userId);
        if (states.isEmpty()) {
            return emptyPreview(userId);
        }

        Map<String, List<MappedLegacyState>> grouped = new LinkedHashMap<>();
        for (UserAbilityStateEntity state : states) {
            Mapping mapping = map(state.getDimensionName(), state.getNormalizedDimension());
            grouped.computeIfAbsent(mapping.bucketName(), ignored -> new ArrayList<>())
                    .add(new MappedLegacyState(state, mapping));
        }

        List<KnowledgeBucketPreview> buckets = grouped.values().stream()
                .map(this::aggregate)
                .sorted(Comparator.comparing(KnowledgeBucketPreview::suggestedAbilityScore).reversed()
                        .thenComparing(KnowledgeBucketPreview::bucketName))
                .limit(MAX_ITEMS)
                .toList();

        List<ManualReviewItem> reviewItems = buckets.stream()
                .filter(KnowledgeBucketPreview::requiresManualReview)
                .map(bucket -> new ManualReviewItem(
                        bucket.bucketName(),
                        bucket.legacyDimensions(),
                        "旧标签没有命中稳定的粗粒度映射规则；正式迁移前请确认名称和归属。"
                ))
                .toList();

        return new MigrationPreview(
                true,
                "LEGACY_RULE_PREVIEW_V1",
                "旧状态保守聚合：最高经验全额保留，其余经验按 35% 折减；能力分和不确定度按可信度与经验权重加权。",
                "核心职能不会从旧技术标签自动迁移或自动加分；需由完成记录重新经过真实交付门禁与职能归因。",
                userId,
                Instant.now(),
                new MigrationSummary(
                        states.size(),
                        buckets.size(),
                        reviewItems.size(),
                        0
                ),
                buckets,
                reviewItems
        );
    }

    private MigrationPreview emptyPreview(String userId) {
        return new MigrationPreview(
                true,
                "LEGACY_RULE_PREVIEW_V1",
                "没有旧能力状态，无需生成迁移分数。",
                "核心职能不会从旧技术标签自动迁移或自动加分；需由完成记录重新经过真实交付门禁与职能归因。",
                userId,
                Instant.now(),
                new MigrationSummary(0, 0, 0, 0),
                List.of(),
                List.of()
        );
    }

    private KnowledgeBucketPreview aggregate(List<MappedLegacyState> items) {
        List<MappedLegacyState> ordered = items.stream()
                .sorted(Comparator.comparing((MappedLegacyState item) -> value(item.state().getExperienceScore()))
                        .reversed())
                .toList();

        BigDecimal experience = ordered.isEmpty()
                ? BigDecimal.ZERO
                : value(ordered.getFirst().state().getExperienceScore()).add(
                        ordered.stream()
                                .skip(1)
                                .map(item -> value(item.state().getExperienceScore()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .multiply(OTHER_EXPERIENCE_WEIGHT)
                );

        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedScore = BigDecimal.ZERO;
        BigDecimal weightedUncertainty = BigDecimal.ZERO;
        BigDecimal weightedMappingConfidence = BigDecimal.ZERO;
        Set<String> dimensions = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        boolean requiresReview = false;
        for (MappedLegacyState item : ordered) {
            BigDecimal weight = evidenceWeight(item.state());
            totalWeight = totalWeight.add(weight);
            weightedScore = weightedScore.add(value(item.state().getAbilityScore()).multiply(weight));
            weightedUncertainty = weightedUncertainty.add(value(item.state().getAbilityUncertainty()).multiply(weight));
            weightedMappingConfidence = weightedMappingConfidence.add(item.mapping().confidence().multiply(weight));
            dimensions.add(nonBlank(item.state().getDimensionName(), item.state().getNormalizedDimension()));
            requiresReview |= item.mapping().requiresManualReview();
            warnings.addAll(item.mapping().warnings());
        }

        BigDecimal abilityScore = divide(weightedScore, totalWeight);
        BigDecimal uncertainty = divide(weightedUncertainty, totalWeight);
        BigDecimal mappingConfidence = divide(weightedMappingConfidence, totalWeight);
        String rank = scoreCalculator.rankFor(abilityScore);

        return new KnowledgeBucketPreview(
                ordered.getFirst().mapping().bucketName(),
                CapabilityBucketType.KNOWLEDGE,
                scale(experience),
                scale(abilityScore),
                scale(uncertainty),
                rank,
                ordered.size(),
                scale(mappingConfidence),
                requiresReview,
                List.copyOf(dimensions),
                List.copyOf(warnings)
        );
    }

    private Mapping map(String dimensionName, String normalizedDimension) {
        String source = nonBlank(dimensionName, normalizedDimension);
        String normalized = normalize(source);
        for (Rule rule : RULES) {
            if (rule.matches(normalized)) {
                return new Mapping(rule.bucketName(), EXACT_RULE_CONFIDENCE, false, List.of());
            }
        }

        String fallbackName = coarseFallback(source);
        return new Mapping(
                fallbackName,
                FALLBACK_CONFIDENCE,
                true,
                List.of("未命中稳定技术规则，保留旧名称等待人工确认")
        );
    }

    private BigDecimal evidenceWeight(UserAbilityStateEntity state) {
        BigDecimal confidence = BigDecimal.ONE.subtract(value(state.getAbilityUncertainty()))
                .max(new BigDecimal("0.1500"));
        double experience = value(state.getExperienceScore()).doubleValue();
        BigDecimal evidence = BigDecimal.valueOf(Math.max(1d, Math.log1p(Math.max(0d, experience))));
        return confidence.multiply(evidence);
    }

    private String coarseFallback(String source) {
        String value = source == null ? "" : source.trim();
        for (String suffix : List.of(
                "编程基础", "基础能力", "基础", "入门", "学习", "应用", "实践能力", "实践", "能力", "技能", "项目"
        )) {
            if (value.length() > suffix.length() + 1 && value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length()).trim();
            }
        }
        return value.isBlank() ? "待人工确认能力" : compact(value, 80);
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户不能为空");
        }
        return userId.trim();
    }

    private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100.0000"))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private String nonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "待人工确认能力" : fallback.trim();
    }

    private String normalize(String value) {
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
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    private String compact(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record MappedLegacyState(UserAbilityStateEntity state, Mapping mapping) {
    }

    private record Mapping(
            String bucketName,
            BigDecimal confidence,
            boolean requiresManualReview,
            List<String> warnings
    ) {
    }

    private record Rule(String bucketName, List<String> terms) {
        private boolean matches(String normalized) {
            return terms.stream().anyMatch(normalized::contains);
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("Spring Boot", List.of("springboot", "springframework", "spring")),
            new Rule("MySQL", List.of("mysql", "sql", "数据库", "索引", "事务")),
            new Rule("Redis", List.of("redis")),
            new Rule("消息队列", List.of("kafka", "rabbitmq", "rocketmq", "消息队列", "mq")),
            new Rule("搜索与检索", List.of("opensearch", "elasticsearch", "全文检索", "搜索引擎")),
            new Rule("RAG", List.of("rag", "retrievalaugmented", "检索增强", "混合召回", "向量检索")),
            new Rule("Agent", List.of("agent", "langgraph", "langchain", "多智能体", "智能体")),
            new Rule("LLM", List.of("llm", "largelanguagemodel", "大模型", "提示词", "prompt")),
            new Rule("Java", List.of("java")),
            new Rule("Python", List.of("python")),
            new Rule("Go", List.of("golang", "go语言", "go开发")),
            new Rule("C++", List.of("cplusplus", "c++", "cpp")),
            new Rule("Linux", List.of("linux")),
            new Rule("Docker", List.of("docker", "容器化")),
            new Rule("前端开发", List.of("vue", "react", "javascript", "typescript", "前端")),
            new Rule("日语", List.of("日语", "jlpt", "n1", "n2", "n3", "n4", "n5")),
            new Rule("英语", List.of("英语", "cet", "toeic", "ielts", "雅思"))
    );

    public record MigrationPreview(
            boolean readOnly,
            String mode,
            String aggregationStrategy,
            String functionMigrationPolicy,
            String userId,
            Instant generatedAt,
            MigrationSummary summary,
            List<KnowledgeBucketPreview> knowledgeBuckets,
            List<ManualReviewItem> manualReviewItems
    ) {
    }

    public record MigrationSummary(
            int legacyStateCount,
            int suggestedKnowledgeBucketCount,
            int manualReviewCount,
            int suggestedFunctionBucketCount
    ) {
    }

    public record KnowledgeBucketPreview(
            String bucketName,
            CapabilityBucketType bucketType,
            BigDecimal suggestedExperienceScore,
            BigDecimal suggestedAbilityScore,
            BigDecimal suggestedAbilityUncertainty,
            String suggestedRank,
            int legacyStateCount,
            BigDecimal mappingConfidence,
            boolean requiresManualReview,
            List<String> legacyDimensions,
            List<String> warnings
    ) {
    }

    public record ManualReviewItem(
            String suggestedBucketName,
            List<String> sourceLegacyDimensions,
            String reason
    ) {
    }
}
