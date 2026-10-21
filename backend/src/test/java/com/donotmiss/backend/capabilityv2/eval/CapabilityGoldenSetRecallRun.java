package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.capabilityv2.CapabilityBucketRepository;
import com.donotmiss.backend.capabilityv2.CapabilityClassificationAuditRepository;
import com.donotmiss.backend.capabilityv2.FunctionBucketCandidateService;
import com.donotmiss.backend.capabilityv2.FunctionBucketSemanticIndex;
import com.donotmiss.backend.capabilityv2.FunctionCapabilityAttributionAgent;
import com.donotmiss.backend.capabilityv2.KnowledgeBucketNormalizationAgent;
import com.donotmiss.backend.capabilityv2.UserKnowledgeBucketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * Manual recall-only comparison: keyword-only vs hybrid (keyword + embedding) candidate recall
 * on the golden set. Uses the real embedding service from {@code .env}; makes no chat-model call.
 *
 * <pre>mvn test -Dtest=CapabilityGoldenSetRecallRun -Dgolden.recall=true</pre>
 *
 * Writes {@code target/golden-set-recall-comparison.json}.
 */
@EnabledIfSystemProperty(named = "golden.recall", matches = "true")
class CapabilityGoldenSetRecallRun {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void compareKeywordAndHybridRecall() throws IOException {
        Map<String, String> env = readDotEnv(Path.of(".env"));
        OpenAiCompatibleLlmClient llm = new OpenAiCompatibleLlmClient(
                MAPPER,
                env.getOrDefault("AI_PROVIDER", "mock"),
                env.getOrDefault("AI_BASE_URL", ""),
                env.getOrDefault("DASHSCOPE_API_KEY", env.getOrDefault("AI_API_KEY", "")),
                env.getOrDefault("AI_MODEL", "qwen-plus"),
                env.getOrDefault("AI_EMBEDDING_MODEL", "text-embedding-v4"),
                false,
                60,
                env.getOrDefault("AI_EMBEDDING_BASE_URL", ""),
                env.getOrDefault("AI_EMBEDDING_API_KEY", "")
        );
        if (!llm.isEmbeddingEnabled()) {
            throw new IllegalStateException("向量模型未配置，无法评测混合召回");
        }
        CapabilityBucketRepository seeds = CapabilityGoldenSetRegressionTest.seedRepository();
        CapabilityGoldenSet goldenSet = CapabilityGoldenSet.load(MAPPER);

        CapabilityGoldenSetEvalService.Report keyword = evalService(
                new FunctionBucketCandidateService(seeds, MAPPER), llm)
                .run(CapabilityGoldenSetEvalService.Mode.DETERMINISTIC, goldenSet, null);
        CapabilityGoldenSetEvalService.Report hybrid = evalService(
                new FunctionBucketCandidateService(seeds, MAPPER, new FunctionBucketSemanticIndex(llm, MAPPER, true)), llm)
                .run(CapabilityGoldenSetEvalService.Mode.DETERMINISTIC, goldenSet, null);

        // The eval report keeps candidate names only; recompute semantic coverage directly to prove embeddings were used.
        FunctionBucketSemanticIndex probeIndex = new FunctionBucketSemanticIndex(llm, MAPPER, true);
        FunctionBucketCandidateService probe = new FunctionBucketCandidateService(seeds, MAPPER, probeIndex);
        List<com.donotmiss.backend.capabilityv2.CapabilityBucketEntity> allBuckets = seeds
                .findByBucketTypeAndOwnerUserIdAndStatusOrderByDisplayNameAsc(
                        com.donotmiss.backend.capabilityv2.CapabilityBucketType.FUNCTION,
                        com.donotmiss.backend.capabilityv2.CapabilityBucketEntity.GLOBAL_OWNER,
                        com.donotmiss.backend.capabilityv2.CapabilityBucketStatus.ACTIVE);
        List<Map<String, Object>> perCase = new ArrayList<>();
        int semanticCases = 0;
        for (int index = 0; index < goldenSet.cases().size(); index++) {
            CapabilityGoldenSet.Case goldenCase = goldenSet.cases().get(index);
            List<FunctionBucketCandidateService.FunctionCandidateMatch> matches = probe.retrieve(goldenCase.toEvidence());
            boolean semantic = matches.stream().anyMatch(FunctionBucketCandidateService.FunctionCandidateMatch::semantic);
            semanticCases += semantic ? 1 : 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", goldenCase.id());
            row.put("category", goldenCase.category());
            row.put("expectedPrimary", goldenCase.function().primary());
            row.put("keywordHit", keyword.cases().get(index).function().recallHit());
            row.put("hybridHit", hybrid.cases().get(index).function().recallHit());
            row.put("semanticUsed", semantic);
            if (!goldenCase.function().primary().isEmpty() && goldenCase.function().gate()) {
                // Where does each expected function sit in the pure semantic ranking of all buckets?
                Map<Long, Double> similarities = probeIndex.similarities(allBuckets, goldenCase.toEvidence()).orElse(Map.of());
                List<Map.Entry<Long, Double>> order = similarities.entrySet().stream()
                        .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()).toList();
                Map<String, String> expectedRanks = new LinkedHashMap<>();
                for (com.donotmiss.backend.capabilityv2.CapabilityBucketEntity bucket : allBuckets) {
                    if (goldenCase.function().primary().contains(bucket.getDisplayName())) {
                        int rank = 1;
                        for (Map.Entry<Long, Double> entry : order) {
                            if (entry.getKey().equals(bucket.getId())) {
                                break;
                            }
                            rank++;
                        }
                        expectedRanks.put(bucket.getDisplayName(),
                                String.format("semantic #%d (%.3f)", rank, similarities.getOrDefault(bucket.getId(), 0.0)));
                    }
                }
                row.put("expectedSemanticRank", expectedRanks);
            }
            row.put("hybridTop10", matches.stream().map(match -> match.candidate().displayName()
                    + (match.semantic() ? String.format(" (%.3f)", match.semanticSimilarity()) : "")
                    + (match.source() == FunctionBucketCandidateService.RecallSource.EXPLICIT_NAME ? " [原文点名]" : "")).toList());
            perCase.add(row);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("embeddingModel", llm.embeddingModeLabel());
        output.put("casesUsingEmbeddings", semanticCases + "/" + goldenSet.cases().size());
        output.put("keywordRecallAt10", keyword.summary().function().recallAt10());
        output.put("hybridRecallAt10", hybrid.summary().function().recallAt10());
        output.put("cases", perCase);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "golden-set-recall-comparison.json"),
                MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(output));
    }

    private CapabilityGoldenSetEvalService evalService(FunctionBucketCandidateService candidates, OpenAiCompatibleLlmClient llm) {
        return new CapabilityGoldenSetEvalService(
                new KnowledgeBucketNormalizationAgent(mock(UserKnowledgeBucketService.class),
                        mock(CapabilityBucketRepository.class), mock(CapabilityClassificationAuditRepository.class), llm, MAPPER),
                new FunctionCapabilityAttributionAgent(candidates, mock(CapabilityClassificationAuditRepository.class), llm, MAPPER),
                MAPPER);
    }

    private Map<String, String> readDotEnv(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int separator = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || separator <= 0) {
                continue;
            }
            values.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return values;
    }
}
