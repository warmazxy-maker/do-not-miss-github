package com.donotmiss.backend.retrieval.eval;

import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventCategory;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.eventquality.EventQualityReportEntity;
import com.donotmiss.backend.retrieval.EventSearchIndexService;
import com.donotmiss.backend.retrieval.RetrievalDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the versioned retrieval evaluation set and the frozen event corpus it is judged against.
 *
 * <p>Two files under {@code retrieval-eval/}:</p>
 * <ul>
 *     <li>{@code retrieval-eval-set.json}: {@code version}, {@code corpusVersion}, the fixed clock
 *     {@code asOf}, a changelog and the cases.</li>
 *     <li>{@code events-<corpusVersion>.json}: the events and their quality reports as they were
 *     when frozen.</li>
 * </ul>
 * <p>The corpus fingerprint is a SHA-256 of the corpus file plus the embedding model label: the
 * evaluation index name carries it, so editing the fixture or switching embedding models builds a
 * fresh index instead of silently reusing vectors from the old one.</p>
 */
@Component
public class RetrievalEvalFixtures {
    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalFixtures.class);
    static final String SET_PATH = "retrieval-eval/retrieval-eval-set.json";
    private static final String INDEX_PREFIX = "do_not_miss_eval_";

    private final ObjectMapper objectMapper;
    private final EventSearchIndexService searchIndex;
    private final String embeddingLabel;

    private volatile RetrievalEvalSet evalSet;
    private volatile FrozenEventCorpus corpus;
    private volatile boolean indexReady;

    public RetrievalEvalFixtures(ObjectMapper objectMapper,
                                 EventSearchIndexService searchIndex,
                                 com.donotmiss.backend.ai.OpenAiCompatibleLlmClient llmClient) {
        this.objectMapper = objectMapper;
        this.searchIndex = searchIndex;
        this.embeddingLabel = searchIndex.isVectorEnabled() ? llmClient.embeddingModeLabel() : "no-vector";
    }

    public RetrievalEvalSet evalSet() {
        RetrievalEvalSet current = evalSet;
        if (current == null) {
            synchronized (this) {
                if (evalSet == null) {
                    evalSet = readEvalSet(objectMapper, read(SET_PATH));
                }
                current = evalSet;
            }
        }
        return current;
    }

    /** The frozen corpus, with its evaluation index built on first use. */
    public FrozenEventCorpus corpus() {
        FrozenEventCorpus current = corpus;
        if (current == null) {
            synchronized (this) {
                if (corpus == null) {
                    byte[] bytes = read(corpusPath(evalSet().corpusVersion()));
                    corpus = readCorpus(objectMapper, bytes, embeddingLabel, searchIndex);
                }
                current = corpus;
            }
        }
        ensureIndex(current);
        return current;
    }

    private void ensureIndex(FrozenEventCorpus current) {
        if (indexReady || !current.searchEnabled()) {
            return;
        }
        synchronized (this) {
            if (indexReady) {
                return;
            }
            if (!searchIndex.indexExists(current.indexName())) {
                int count = searchIndex.rebuildIndex(current.indexName(), current.indexedEvents());
                log.info("Built retrieval evaluation index {} with {} frozen events.", current.indexName(), count);
            }
            indexReady = true;
        }
    }

    /** The evaluation set file as committed, for building its next version. */
    public JsonNode rawEvalSet() {
        return readTree(read(SET_PATH));
    }

    /** The current corpus file as committed, for building its next version. */
    public JsonNode rawCorpus() {
        return readTree(read(corpusPath(evalSet().corpusVersion())));
    }

    private JsonNode readTree(byte[] bytes) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse evaluation fixture", ex);
        }
    }

    public static String corpusFileName(String corpusVersion) {
        return corpusPath(corpusVersion).substring("retrieval-eval/".length());
    }

    static String corpusPath(String corpusVersion) {
        return "retrieval-eval/events-" + corpusVersion.replaceFirst("^events-", "") + ".json";
    }

    // ---- Parsing (static so tests need no Spring context) ----

    static RetrievalEvalSet readEvalSet(ObjectMapper objectMapper, byte[] bytes) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            List<RetrievalDtos.EvalCase> cases = objectMapper.convertValue(
                    root.path("cases"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RetrievalDtos.EvalCase.class));
            return new RetrievalEvalSet(
                    required(root, "version"),
                    required(root, "corpusVersion"),
                    OffsetDateTime.parse(required(root, "asOf")),
                    root.path("labelStatus").asText(""),
                    cases);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + SET_PATH, ex);
        }
    }

    static FrozenEventCorpus readCorpus(ObjectMapper objectMapper, byte[] bytes, String embeddingLabel,
                                        EventSearchIndexService searchIndex) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            String version = required(root, "corpusVersion");
            List<EventEntity> events = new ArrayList<>();
            Map<Long, EventQualityReportEntity> reports = new LinkedHashMap<>();
            for (JsonNode node : root.path("events")) {
                EventEntity event = toEvent(node);
                events.add(event);
                JsonNode report = node.path("qualityReport");
                if (report.isObject()) {
                    reports.put(event.getId(), toQualityReport(objectMapper, event.getId(), report));
                }
            }
            String fingerprint = fingerprint(bytes, embeddingLabel);
            String indexName = INDEX_PREFIX + version.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase(Locale.ROOT)
                    + "_" + fingerprint.substring(0, 8);
            return new FrozenEventCorpus(version, fingerprint, indexName, events, reports, searchIndex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read the frozen evaluation corpus", ex);
        }
    }

    static String fingerprint(byte[] corpusBytes, String embeddingLabel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(corpusBytes);
            digest.update(("|embedding=" + embeddingLabel).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static EventEntity toEvent(JsonNode node) {
        EventEntity event = EventEntity.detached(node.path("id").asLong());
        event.setTitle(node.path("title").asText());
        event.setOrganizationName(node.path("organizationName").asText());
        event.setCategory(EventCategory.fromText(node.path("category").asText()));
        event.setStartTime(LocalDateTime.parse(node.path("startTime").asText()));
        event.setEndTime(LocalDateTime.parse(node.path("endTime").asText()));
        event.setLocation(node.path("location").asText());
        event.setContent(node.path("content").asText());
        event.setBenefitType(BenefitType.fromText(node.path("benefitType").asText()));
        event.setSkill(node.path("skill").isNull() ? null : node.path("skill").asText());
        event.setMoneyAmount(node.path("moneyAmount").isNull() || node.path("moneyAmount").isMissingNode()
                ? null : new BigDecimal(node.path("moneyAmount").asText()));
        return event;
    }

    private static EventQualityReportEntity toQualityReport(ObjectMapper objectMapper, Long eventId, JsonNode node)
            throws IOException {
        EventQualityReportEntity report = new EventQualityReportEntity();
        report.setEventId(eventId);
        report.setQualityScore(node.path("qualityScore").asInt());
        report.setQualityLevel(node.path("qualityLevel").asText(null));
        report.setDifficulty(node.path("difficulty").asText(null));
        report.setSummary(node.path("summary").asText(null));
        report.setTargetStudentsJson(jsonText(objectMapper, node.path("targetStudentsJson")));
        report.setPrerequisitesJson(jsonText(objectMapper, node.path("prerequisitesJson")));
        report.setLearningOutcomesJson(jsonText(objectMapper, node.path("learningOutcomesJson")));
        report.setExtractedTagsJson(jsonText(objectMapper, node.path("extractedTagsJson")));
        report.setAbilityImpactsJson(jsonText(objectMapper, node.path("abilityImpactsJson")));
        report.setRiskFlagsJson(jsonText(objectMapper, node.path("riskFlagsJson")));
        return report;
    }

    /** The fixture stores these as JSON arrays for readability; the entity keeps JSON text. */
    private static String jsonText(ObjectMapper objectMapper, JsonNode node) throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return "[]";
        }
        return node.isTextual() ? node.asText() : objectMapper.writeValueAsString(node);
    }

    private static String required(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Evaluation fixture is missing '" + field + "'");
        }
        return value;
    }

    private static byte[] read(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path, ex);
        }
    }
}
