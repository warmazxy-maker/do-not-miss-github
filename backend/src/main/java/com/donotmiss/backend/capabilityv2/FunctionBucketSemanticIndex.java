package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Semantic half of the function candidate recall: one embedding per seed function bucket,
 * kept in memory, and a cosine similarity against each piece of evidence.
 *
 * <p>Keyword recall only sees identical characters, so "RabbitMQ" never meets "消息队列" and
 * "超声波避障" meets the medical "超声". Embeddings compare meaning instead.</p>
 *
 * <p>Design choices:</p>
 * <ul>
 *     <li>373 vectors are about 2 MB as {@code float[]}, so they live in memory and are
 *     rebuilt after a restart with a few batched requests, instead of needing a table.</li>
 *     <li>The index is rebuilt when the bucket set or the embedding model changes
 *     (fingerprint of ids, texts and model label).</li>
 *     <li>Every failure returns empty, never an exception: semantic recall is an enhancement on
 *     top of keyword recall, not a reason to stop scoring. A failed build is retried after a
 *     cool-down rather than on every message.</li>
 * </ul>
 */
@Component
public class FunctionBucketSemanticIndex {
    private static final Logger log = LoggerFactory.getLogger(FunctionBucketSemanticIndex.class);
    private static final int BATCH_SIZE = 64;
    private static final int EVIDENCE_TEXT_LIMIT = 1200;
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    private volatile Snapshot snapshot;
    private volatile Instant lastFailureAt;

    public FunctionBucketSemanticIndex(OpenAiCompatibleLlmClient llmClient,
                                       ObjectMapper objectMapper,
                                       @Value("${app.capability-v2.recall.semantic-enabled:true}") boolean enabled) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public boolean available() {
        return enabled && llmClient.isEmbeddingEnabled();
    }

    /**
     * Cosine similarity of the evidence to every bucket, keyed by bucket id. Empty when semantic
     * recall is off, the index cannot be built, or the evidence cannot be embedded.
     */
    public Optional<Map<Long, Double>> similarities(List<CapabilityBucketEntity> buckets, CapabilityEvidenceInput evidence) {
        if (!available() || buckets.isEmpty()) {
            return Optional.empty();
        }
        Snapshot index = ensureIndex(buckets);
        if (index == null) {
            return Optional.empty();
        }
        Optional<float[]> query = llmClient.embedding(evidenceText(evidence)).map(FunctionBucketSemanticIndex::toUnitVector);
        if (query.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        index.vectors().forEach((bucketId, vector) -> scores.put(bucketId, dot(query.get(), vector)));
        return Optional.of(scores);
    }

    private Snapshot ensureIndex(List<CapabilityBucketEntity> buckets) {
        String fingerprint = fingerprint(buckets);
        Snapshot current = snapshot;
        if (current != null && current.fingerprint().equals(fingerprint)) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && current.fingerprint().equals(fingerprint)) {
                return current;
            }
            if (lastFailureAt != null && Instant.now().isBefore(lastFailureAt.plus(RETRY_AFTER_FAILURE))) {
                return null;
            }
            Snapshot built = build(buckets, fingerprint);
            if (built == null) {
                lastFailureAt = Instant.now();
                log.warn("Function bucket semantic index could not be built; keyword recall only for {}.", RETRY_AFTER_FAILURE);
                return null;
            }
            snapshot = built;
            lastFailureAt = null;
            log.info("Function bucket semantic index ready: {} buckets, model={}", built.vectors().size(), llmClient.embeddingModeLabel());
            return built;
        }
    }

    private Snapshot build(List<CapabilityBucketEntity> buckets, String fingerprint) {
        Map<Long, float[]> vectors = new LinkedHashMap<>();
        for (int start = 0; start < buckets.size(); start += BATCH_SIZE) {
            List<CapabilityBucketEntity> batch = buckets.subList(start, Math.min(start + BATCH_SIZE, buckets.size()));
            Optional<List<List<Double>>> embedded = llmClient.embeddings(batch.stream().map(this::bucketText).toList());
            if (embedded.isEmpty()) {
                return null;
            }
            for (int index = 0; index < batch.size(); index++) {
                vectors.put(batch.get(index).getId(), toUnitVector(embedded.get().get(index)));
            }
        }
        return new Snapshot(fingerprint, Map.copyOf(vectors));
    }

    /** Name first, then the absorbed sub-skills: the same information the keyword recall uses. */
    String bucketText(CapabilityBucketEntity bucket) {
        List<String> parts = new ArrayList<>();
        parts.add("职能：" + bucket.getDisplayName());
        if (bucket.getDomainName() != null || bucket.getGroupName() != null) {
            parts.add("领域：" + String.join(" / ", nonBlank(bucket.getDomainName(), bucket.getGroupName())));
        }
        if (bucket.getDescription() != null && !bucket.getDescription().isBlank()) {
            parts.add("包含：" + bucket.getDescription().trim());
        }
        List<String> aliases = aliases(bucket.getAliasesJson());
        if (!aliases.isEmpty()) {
            parts.add("别名：" + String.join("、", aliases));
        }
        return String.join("；", parts);
    }

    static String evidenceText(CapabilityEvidenceInput evidence) {
        List<String> parts = new ArrayList<>();
        parts.add(evidence.title());
        parts.add(evidence.description());
        parts.addAll(evidence.evidenceFacts());
        parts.add(evidence.reflection());
        String text = String.join("；", parts.stream().filter(part -> part != null && !part.isBlank()).toList());
        return text.length() <= EVIDENCE_TEXT_LIMIT ? text : text.substring(0, EVIDENCE_TEXT_LIMIT);
    }

    private String fingerprint(List<CapabilityBucketEntity> buckets) {
        StringBuilder material = new StringBuilder(llmClient.embeddingModeLabel());
        for (CapabilityBucketEntity bucket : buckets) {
            material.append('|').append(bucket.getId()).append(':').append(bucketText(bucket).hashCode());
        }
        return Integer.toHexString(material.toString().hashCode()) + ":" + buckets.size();
    }

    private List<String> aliases(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).limit(20).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    /** Normalizing once makes every later cosine a plain dot product. */
    private static float[] toUnitVector(List<Double> values) {
        double norm = 0;
        for (Double value : values) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        float[] vector = new float[values.size()];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = norm == 0 ? 0f : (float) (values.get(index) / norm);
        }
        return vector;
    }

    private static double dot(float[] left, float[] right) {
        if (left.length != right.length) {
            return 0;
        }
        double sum = 0;
        for (int index = 0; index < left.length; index++) {
            sum += left[index] * right[index];
        }
        return sum;
    }

    private record Snapshot(String fingerprint, Map<Long, float[]> vectors) {
    }
}
