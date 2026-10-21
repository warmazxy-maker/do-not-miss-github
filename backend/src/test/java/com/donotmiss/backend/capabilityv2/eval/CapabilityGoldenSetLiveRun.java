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
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * Manual LIVE run of the golden set with the real model configured in {@code .env}.
 * Never part of a normal build: it spends model quota and its result is not deterministic.
 *
 * <pre>mvn test -Dtest=CapabilityGoldenSetLiveRun -Dgolden.live=true</pre>
 * Add {@code -Dgolden.semantic=false} to measure keyword-only recall for an A/B comparison.
 *
 * Writes {@code target/golden-set-live-report.json} and {@code target/golden-set-cassette.json};
 * copy the cassette to {@code src/main/resources/capability-v2/} after reviewing it to enable REPLAY.
 */
@EnabledIfSystemProperty(named = "golden.live", matches = "true")
class CapabilityGoldenSetLiveRun {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void runAgainstTheConfiguredModel() throws IOException {
        Map<String, String> env = readDotEnv(Path.of(".env"));
        OpenAiCompatibleLlmClient llm = new OpenAiCompatibleLlmClient(
                MAPPER,
                env.getOrDefault("AI_PROVIDER", "mock"),
                env.getOrDefault("AI_BASE_URL", ""),
                env.getOrDefault("DASHSCOPE_API_KEY", env.getOrDefault("AI_API_KEY", "")),
                env.getOrDefault("AI_MODEL", "qwen-plus"),
                env.getOrDefault("AI_EMBEDDING_MODEL", "text-embedding-v4"),
                Boolean.parseBoolean(env.getOrDefault("AI_QWEN_THINKING_ENABLED", "false")),
                60,
                env.getOrDefault("AI_EMBEDDING_BASE_URL", ""),
                env.getOrDefault("AI_EMBEDDING_API_KEY", "")
        );
        if (!llm.isEnabled()) {
            throw new IllegalStateException("模型未配置，无法进行 LIVE 评测");
        }
        // Same hybrid recall as production when embeddings are configured; keyword-only otherwise.
        boolean semantic = Boolean.parseBoolean(System.getProperty("golden.semantic", "true"));
        FunctionBucketCandidateService candidates = new FunctionBucketCandidateService(
                CapabilityGoldenSetRegressionTest.seedRepository(), MAPPER,
                new FunctionBucketSemanticIndex(llm, MAPPER, semantic));
        CapabilityGoldenSetEvalService service = new CapabilityGoldenSetEvalService(
                new KnowledgeBucketNormalizationAgent(mock(UserKnowledgeBucketService.class),
                        mock(CapabilityBucketRepository.class), mock(CapabilityClassificationAuditRepository.class), llm, MAPPER),
                new FunctionCapabilityAttributionAgent(candidates, mock(CapabilityClassificationAuditRepository.class), llm, MAPPER),
                MAPPER);

        CapabilityGoldenSetEvalService.Report report = service.run(
                CapabilityGoldenSetEvalService.Mode.LIVE, CapabilityGoldenSet.load(MAPPER), null);

        ObjectMapper pretty = MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "golden-set-live-report.json"), pretty.writeValueAsString(report));
        Files.writeString(Path.of("target", "golden-set-cassette.json"), pretty.writeValueAsString(report.cassette()));
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
