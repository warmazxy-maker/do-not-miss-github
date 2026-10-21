package com.donotmiss.backend.capabilityv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 09 / Stage 16.5: retrieves a small, explainable candidate set from the global function taxonomy.
 *
 * <p>Semantic first, keywords only as a safety net for explicit mentions:</p>
 * <ol>
 *     <li>The top {@code limit} buckets by embedding cosine similarity ({@link FunctionBucketSemanticIndex}).</li>
 *     <li>Plus any bucket whose name appears verbatim in the evidence ("负责后端开发"), even if its
 *     semantic rank is lower, up to {@link #MAX_CANDIDATES} in total.</li>
 * </ol>
 * <p>Why not fuse all keyword hits: on the Stage 16 golden set an RRF fusion of keyword and semantic
 * ranks reached recall@10 0.88 while semantic alone reached 0.92. Partial keyword matches
 * ("超声" → 医学影像, "流程" → 婚礼与庆典策划) added no hit and pushed correct candidates out of
 * the top 10. A verbatim job-function name, by contrast, is a strong signal worth keeping.</p>
 * <p>Without embeddings the service returns the keyword ranking unchanged.
 * The LLM still makes the final judgment, and only within this candidate set.</p>
 */
@Service
public class FunctionBucketCandidateService {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_CANDIDATES = 12;

    /** Why a bucket is in the candidate set; kept in the trace so a bad candidate can be explained. */
    public enum RecallSource { SEMANTIC, EXPLICIT_NAME, KEYWORD, RECORDED }
    private static final Set<String> GENERIC_TERMS = Set.of(
            "学习", "完成", "参加", "活动", "项目", "实践", "能力", "工作", "相关", "负责", "任务", "经验"
    );

    private final CapabilityBucketRepository bucketRepository;
    private final ObjectMapper objectMapper;
    private final FunctionBucketSemanticIndex semanticIndex;

    @Autowired
    public FunctionBucketCandidateService(CapabilityBucketRepository bucketRepository,
                                          ObjectMapper objectMapper,
                                          FunctionBucketSemanticIndex semanticIndex) {
        this.bucketRepository = bucketRepository;
        this.objectMapper = objectMapper;
        this.semanticIndex = semanticIndex;
    }

    /** Keyword-only recall, used by deterministic tests that must not touch the network. */
    public FunctionBucketCandidateService(CapabilityBucketRepository bucketRepository,
                                          ObjectMapper objectMapper) {
        this(bucketRepository, objectMapper, null);
    }

    public List<FunctionCandidateMatch> retrieve(CapabilityEvidenceInput evidence) {
        return retrieve(evidence, DEFAULT_LIMIT);
    }

    /**
     * Rebuilds a previously recorded candidate list by bucket name, in the recorded order. Used by
     * golden-set replay so recorded model answers are judged against the same candidates the model saw,
     * independent of whether embeddings are reachable during the replay.
     */
    public List<FunctionCandidateMatch> byNames(List<String> displayNames) {
        Map<String, CapabilityBucketEntity> byName = new HashMap<>();
        bucketRepository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                CapabilityBucketType.FUNCTION, CapabilityBucketEntity.GLOBAL_OWNER, CapabilityBucketStatus.ACTIVE
        ).forEach(bucket -> byName.putIfAbsent(bucket.getDisplayName(), bucket));
        return displayNames.stream()
                .map(byName::get)
                .filter(java.util.Objects::nonNull)
                .map(bucket -> new FunctionCandidateMatch(toCandidate(bucket), confidence(0), 0, List.of(), null, RecallSource.RECORDED))
                .toList();
    }

    public List<FunctionCandidateMatch> retrieve(CapabilityEvidenceInput evidence, int limit) {
        if (evidence == null || limit <= 0) {
            return List.of();
        }
        List<EvidenceText> texts = evidenceTexts(evidence);
        if (texts.isEmpty()) {
            return List.of();
        }
        int cappedLimit = Math.min(limit, MAX_CANDIDATES);
        List<CapabilityBucketEntity> buckets = bucketRepository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                CapabilityBucketType.FUNCTION,
                CapabilityBucketEntity.GLOBAL_OWNER,
                CapabilityBucketStatus.ACTIVE
        );
        List<FunctionCandidateMatch> lexical = buckets.stream().map(bucket -> score(bucket, texts)).toList();
        List<FunctionCandidateMatch> keywordRanking = lexical.stream()
                .filter(match -> match.rawScore() > 0)
                .sorted(Comparator.comparingInt(FunctionCandidateMatch::rawScore).reversed()
                        .thenComparing(match -> match.candidate().displayName()))
                .toList();

        Optional<Map<Long, Double>> similarities = semanticIndex == null
                ? Optional.empty()
                : semanticIndex.similarities(buckets, evidence);
        if (similarities.isEmpty()) {
            return keywordRanking.stream().limit(cappedLimit).toList();
        }
        return semanticFirst(lexical, keywordRanking, texts, similarities.get(), cappedLimit);
    }

    private List<FunctionCandidateMatch> semanticFirst(List<FunctionCandidateMatch> lexical,
                                                       List<FunctionCandidateMatch> keywordRanking,
                                                       List<EvidenceText> texts,
                                                       Map<Long, Double> similarities,
                                                       int limit) {
        Map<Long, FunctionCandidateMatch> lexicalById = new HashMap<>();
        lexical.forEach(match -> lexicalById.put(match.candidate().bucketId(), match));

        LinkedHashMap<Long, FunctionCandidateMatch> selected = new LinkedHashMap<>();
        similarities.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> selected.put(entry.getKey(),
                        withSource(lexicalById.get(entry.getKey()), entry.getValue(), RecallSource.SEMANTIC)));

        // Safety net: a job-function name written out in the evidence is kept even if its semantic rank is lower.
        for (FunctionCandidateMatch match : keywordRanking) {
            if (selected.size() >= MAX_CANDIDATES) {
                break;
            }
            Long bucketId = match.candidate().bucketId();
            if (!selected.containsKey(bucketId) && mentionsName(texts, match.candidate().displayName())) {
                selected.put(bucketId, withSource(match, similarities.get(bucketId), RecallSource.EXPLICIT_NAME));
            }
        }
        return List.copyOf(selected.values());
    }

    private boolean mentionsName(List<EvidenceText> texts, String displayName) {
        String name = normalize(displayName);
        return name.length() >= 2 && texts.stream().anyMatch(text -> text.normalizedText().contains(name));
    }

    private FunctionCandidateMatch withSource(FunctionCandidateMatch match, Double similarity, RecallSource source) {
        return new FunctionCandidateMatch(
                match.candidate(),
                match.retrievalConfidence(),
                match.rawScore(),
                match.matchedTerms(),
                similarity == null ? null : round(similarity),
                source
        );
    }

    private static Double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private FunctionCandidateMatch score(CapabilityBucketEntity bucket, List<EvidenceText> texts) {
        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        int score = 0;
        for (String term : bucketTerms(bucket)) {
            for (EvidenceText evidence : texts) {
                int matchedLength = longestCommonSubstring(term, evidence.normalizedText());
                if (evidence.normalizedText().contains(term)) {
                    score += evidence.weight() * (term.equals(normalize(bucket.getDisplayName())) ? 42 : 18);
                    matchedTerms.add(term);
                } else if (matchedLength >= 3) {
                    score += evidence.weight() * matchedLength * 3;
                    matchedTerms.add(term);
                }
            }
        }
        return new FunctionCandidateMatch(
                toCandidate(bucket),
                confidence(score),
                score,
                List.copyOf(matchedTerms).subList(0, Math.min(8, matchedTerms.size()))
        );
    }

    private List<EvidenceText> evidenceTexts(CapabilityEvidenceInput evidence) {
        List<EvidenceText> texts = new ArrayList<>();
        addText(texts, evidence.title(), 4);
        addText(texts, evidence.description(), 3);
        addText(texts, evidence.reflection(), 2);
        for (String fact : evidence.evidenceFacts()) {
            addText(texts, fact, 3);
        }
        addText(texts, evidence.qualityReportJson(), 1);
        return List.copyOf(texts);
    }

    private void addText(List<EvidenceText> texts, String raw, int weight) {
        String normalized = normalize(raw);
        if (!normalized.isBlank()) {
            texts.add(new EvidenceText(normalized, weight));
        }
    }

    private List<String> bucketTerms(CapabilityBucketEntity bucket) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerm(terms, bucket.getDisplayName());
        for (String alias : aliases(bucket.getAliasesJson())) {
            addTerm(terms, alias);
        }
        addDescriptionTerms(terms, bucket.getDescription());
        return List.copyOf(terms);
    }

    private void addDescriptionTerms(Set<String> terms, String description) {
        if (description == null || description.isBlank()) {
            return;
        }
        for (String part : description.split("[、,，;；/\\s]+")) {
            addTerm(terms, part);
        }
    }

    private void addTerm(Set<String> terms, String raw) {
        String term = normalize(raw);
        if (term.length() >= 2 && !GENERIC_TERMS.contains(term)) {
            terms.add(term);
        }
    }

    private CapabilityV2Dtos.BucketCandidate toCandidate(CapabilityBucketEntity bucket) {
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

    private List<String> aliases(String aliasesJson) {
        if (aliasesJson == null || aliasesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(aliasesJson, new TypeReference<>() { });
            return values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .limit(40)
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private BigDecimal confidence(int rawScore) {
        if (rawScore <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        double bounded = 1d - Math.exp(-rawScore / 55d);
        return BigDecimal.valueOf(Math.min(0.9900d, bounded)).setScale(4, RoundingMode.HALF_UP);
    }

    static String normalize(String value) {
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

    private int longestCommonSubstring(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        int[] previous = new int[right.length() + 1];
        int longest = 0;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                if (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1)) {
                    current[rightIndex] = previous[rightIndex - 1] + 1;
                    longest = Math.max(longest, current[rightIndex]);
                }
            }
            previous = current;
        }
        return longest;
    }

    /**
     * @param retrievalConfidence keyword confidence derived from {@code rawScore}
     * @param semanticSimilarity  cosine similarity to the evidence; null when semantic recall was not used
     * @param source              why this bucket is a candidate
     */
    public record FunctionCandidateMatch(
            CapabilityV2Dtos.BucketCandidate candidate,
            BigDecimal retrievalConfidence,
            int rawScore,
            List<String> matchedTerms,
            Double semanticSimilarity,
            RecallSource source
    ) {
        public FunctionCandidateMatch(CapabilityV2Dtos.BucketCandidate candidate,
                                      BigDecimal retrievalConfidence,
                                      int rawScore,
                                      List<String> matchedTerms) {
            this(candidate, retrievalConfidence, rawScore, matchedTerms, null, RecallSource.KEYWORD);
        }

        public boolean semantic() {
            return semanticSimilarity != null;
        }
    }

    private record EvidenceText(String normalizedText, int weight) {
    }
}
