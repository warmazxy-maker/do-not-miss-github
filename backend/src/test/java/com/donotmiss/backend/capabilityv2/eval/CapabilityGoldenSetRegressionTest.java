package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.capabilityv2.CapabilityBucketEntity;
import com.donotmiss.backend.capabilityv2.CapabilityBucketRepository;
import com.donotmiss.backend.capabilityv2.CapabilityBucketStatus;
import com.donotmiss.backend.capabilityv2.CapabilityBucketType;
import com.donotmiss.backend.capabilityv2.CapabilityClassificationAuditRepository;
import com.donotmiss.backend.capabilityv2.FunctionBucketCandidateService;
import com.donotmiss.backend.capabilityv2.FunctionBucketSeedCatalog;
import com.donotmiss.backend.capabilityv2.FunctionCapabilityAttributionAgent;
import com.donotmiss.backend.capabilityv2.KnowledgeBucketNormalizationAgent;
import com.donotmiss.backend.capabilityv2.UserKnowledgeBucketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 16 regression gate. Runs the golden set through the real recall, delivery gate and
 * validation code against the real seed library, with no database and no model, and fails if
 * a metric drops below the recorded baseline in {@code capability-v2/golden-set-baseline.json}.
 */
class CapabilityGoldenSetRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void deterministicMetricsDoNotRegress() throws IOException {
        CapabilityGoldenSetEvalService.Report report = service().run(
                CapabilityGoldenSetEvalService.Mode.DETERMINISTIC, CapabilityGoldenSet.load(MAPPER), null);
        writeReport("golden-set-deterministic-report.json", report);

        JsonNode baseline = baseline().path("deterministic");
        CapabilityGoldenSetScorer.FunctionSummary function = report.summary().function();
        assertEquals(baseline.path("caseCount").asInt(), report.summary().caseCount(),
                "golden set size changed: update the baseline together with the labels");
        // The gate stops only coach logs by design; what must never happen is blocking real work.
        assertTrue(function.gateFalseBlock() <= baseline.path("gateFalseBlock").asInt(),
                "the gate blocked real work again: " + function.gateFalseBlock());
        assertTrue(function.recallAt10() >= baseline.path("recallAt10").asDouble(),
                "keyword-only candidate recall@10 regressed: " + function.recallAt10());
    }

    @Test
    void recordedModelAnswersStillScoreAtLeastAsWellAfterCodeChanges() throws IOException {
        GoldenSetCassette cassette = GoldenSetCassette.load(MAPPER);
        Assumptions.assumeTrue(cassette != null, "no cassette recorded yet; run LIVE once and save it");

        CapabilityGoldenSetEvalService.Report report = service().run(
                CapabilityGoldenSetEvalService.Mode.REPLAY, CapabilityGoldenSet.load(MAPPER), cassette);
        writeReport("golden-set-replay-report.json", report);

        JsonNode baseline = baseline().path("replay");
        Assumptions.assumeTrue(!baseline.isMissingNode(), "no replay baseline recorded yet");
        assertTrue(report.summary().knowledge().f1() >= baseline.path("knowledgeF1").asDouble());
        assertTrue(report.summary().function().primaryHitRate() >= baseline.path("primaryHitRate").asDouble());
        assertTrue(report.summary().function().misattributionRate() <= baseline.path("misattributionRate").asDouble());
    }

    private CapabilityGoldenSetEvalService service() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        FunctionBucketCandidateService candidates = new FunctionBucketCandidateService(seedRepository(), MAPPER);
        return new CapabilityGoldenSetEvalService(
                new KnowledgeBucketNormalizationAgent(mock(UserKnowledgeBucketService.class),
                        mock(CapabilityBucketRepository.class), mock(CapabilityClassificationAuditRepository.class), llm, MAPPER),
                new FunctionCapabilityAttributionAgent(candidates, mock(CapabilityClassificationAuditRepository.class), llm, MAPPER),
                MAPPER);
    }

    /** The same rows the startup import writes, built from the real seed file. */
    static CapabilityBucketRepository seedRepository() {
        FunctionBucketSeedCatalog catalog = new FunctionBucketSeedCatalog();
        catalog.reload();
        Map<String, FunctionBucketSeedCatalog.FunctionBucketSeed> unique = new LinkedHashMap<>();
        catalog.all().forEach(seed -> unique.putIfAbsent(seed.normalizedName(), seed));
        List<CapabilityBucketEntity> buckets = new ArrayList<>();
        long id = 1;
        for (FunctionBucketSeedCatalog.FunctionBucketSeed seed : unique.values()) {
            CapabilityBucketEntity bucket = new CapabilityBucketEntity();
            ReflectionTestUtils.setField(bucket, "id", id++);
            bucket.setBucketType(CapabilityBucketType.FUNCTION);
            bucket.setOwnerUserId(CapabilityBucketEntity.GLOBAL_OWNER);
            bucket.setNormalizedName(seed.normalizedName());
            bucket.setDisplayName(seed.displayName());
            bucket.setDomainName(seed.domainName());
            bucket.setGroupName(seed.groupName());
            bucket.setDescription(seed.description());
            try {
                bucket.setAliasesJson(MAPPER.writeValueAsString(seed.aliases()));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            bucket.setStatus(CapabilityBucketStatus.ACTIVE);
            buckets.add(bucket);
        }
        CapabilityBucketRepository repository = mock(CapabilityBucketRepository.class);
        when(repository.findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                CapabilityBucketType.FUNCTION, CapabilityBucketEntity.GLOBAL_OWNER, CapabilityBucketStatus.ACTIVE))
                .thenReturn(buckets);
        return repository;
    }

    private JsonNode baseline() throws IOException {
        try (InputStream stream = new ClassPathResource("capability-v2/golden-set-baseline.json").getInputStream()) {
            return MAPPER.readTree(stream);
        }
    }

    private void writeReport(String name, Object report) throws IOException {
        Path target = Path.of("target", name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(report));
    }
}
