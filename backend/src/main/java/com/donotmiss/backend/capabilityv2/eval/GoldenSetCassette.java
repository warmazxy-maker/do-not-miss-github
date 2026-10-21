package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.capabilityv2.FunctionBucketCandidateService;
import com.donotmiss.backend.capabilityv2.FunctionCapabilityAttributionAgent;
import com.donotmiss.backend.capabilityv2.KnowledgeBucketNormalizationAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw model answers recorded by one LIVE run, replayed later without calling the model.
 *
 * <p>Function bucket ids differ between a real database and the in-memory seed catalog used
 * in tests, so the cassette also stores each recalled candidate's name and replay maps ids by
 * name. Knowledge fixtures use stable synthetic ids and need no mapping.</p>
 */
public record GoldenSetCassette(String goldenSetVersion, Map<String, Entry> cases) {
    public static final String RESOURCE = "capability-v2/golden-set-cassette.json";

    /** Returns null when no cassette has been recorded yet. */
    public static GoldenSetCassette load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream stream = resource.getInputStream()) {
            return objectMapper.readValue(stream, GoldenSetCassette.class);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取评测录制文件：" + RESOURCE, exception);
        }
    }

    public record Entry(
            KnowledgeBucketNormalizationAgent.ModelResponse knowledge,
            FunctionCapabilityAttributionAgent.ModelResponse function,
            Map<Long, String> functionCandidateNames
    ) {
        static Entry of(KnowledgeBucketNormalizationAgent.KnowledgeDecision knowledge,
                        FunctionCapabilityAttributionAgent.FunctionDecision function,
                        List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates) {
            Map<Long, String> names = new LinkedHashMap<>();
            candidates.forEach(match -> names.put(match.candidate().bucketId(), match.candidate().displayName()));
            return new Entry(
                    knowledge == null ? null : knowledge.rawResponse(),
                    function == null ? null : function.rawResponse(),
                    names
            );
        }

        /** Rewrites recorded bucket ids to the ids of the same-named candidates in this run. */
        FunctionCapabilityAttributionAgent.ModelResponse remapFunction(
                List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates) {
            Map<String, Long> currentIds = new LinkedHashMap<>();
            candidates.forEach(match -> currentIds.put(match.candidate().displayName(), match.candidate().bucketId()));
            List<FunctionCapabilityAttributionAgent.ModelAssignment> assignments = function.assignments() == null
                    ? List.of()
                    : function.assignments().stream().map(item -> item == null ? null : new FunctionCapabilityAttributionAgent.ModelAssignment(
                            item.action(),
                            currentIds.getOrDefault(functionCandidateNames.get(item.bucketId()), item.bucketId()),
                            item.role(),
                            item.relevance(),
                            item.confidence(),
                            item.claimedOutcome(),
                            item.evidenceFindings(),
                            item.riskFlags(),
                            item.reason()
                    )).toList();
            return new FunctionCapabilityAttributionAgent.ModelResponse(function.summary(), assignments);
        }
    }
}
