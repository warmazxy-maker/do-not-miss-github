package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.capabilityv2.CapabilityEvidenceInput;
import com.donotmiss.backend.capabilityv2.CapabilityModelUnavailableException;
import com.donotmiss.backend.capabilityv2.CapabilityV2Dtos;
import com.donotmiss.backend.capabilityv2.FunctionBucketCandidateService;
import com.donotmiss.backend.capabilityv2.FunctionCapabilityAttributionAgent;
import com.donotmiss.backend.capabilityv2.KnowledgeBucketNormalizationAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the golden set against the real agents without writing anything.
 *
 * <ul>
 *     <li>{@link Mode#DETERMINISTIC}: gate and candidate recall only. Free, no model,
 *     and the same code path as production, so it runs as a Maven regression test.</li>
 *     <li>{@link Mode#LIVE}: calls the model through the agents' {@code decide}. The report includes a
 *     cassette of the raw model answers, which can be saved as
 *     {@code capability-v2/golden-set-cassette.json}.</li>
 *     <li>{@link Mode#REPLAY}: feeds a saved cassette through the agents' pure {@code validate}, using the
 *     candidate list recorded with it. Catches regressions in Java validation and scoring without paying
 *     for model calls; recall itself is measured by DETERMINISTIC (keywords) and the manual recall run
 *     (embeddings).</li>
 * </ul>
 */
@Service
public class CapabilityGoldenSetEvalService {
    public enum Mode { DETERMINISTIC, LIVE, REPLAY }

    private final KnowledgeBucketNormalizationAgent knowledgeAgent;
    private final FunctionCapabilityAttributionAgent functionAgent;
    private final ObjectMapper objectMapper;

    public CapabilityGoldenSetEvalService(KnowledgeBucketNormalizationAgent knowledgeAgent,
                                          FunctionCapabilityAttributionAgent functionAgent,
                                          ObjectMapper objectMapper) {
        this.knowledgeAgent = knowledgeAgent;
        this.functionAgent = functionAgent;
        this.objectMapper = objectMapper;
    }

    public Report run(Mode mode) {
        return run(mode, CapabilityGoldenSet.load(objectMapper),
                mode == Mode.REPLAY ? GoldenSetCassette.load(objectMapper) : null);
    }

    public Report run(Mode mode, CapabilityGoldenSet goldenSet, GoldenSetCassette cassette) {
        if (mode == Mode.REPLAY && cassette == null) {
            throw new IllegalStateException("REPLAY 模式需要先用 LIVE 模式生成并保存 " + GoldenSetCassette.RESOURCE);
        }
        List<CapabilityGoldenSetScorer.CaseResult> results = new ArrayList<>();
        Map<String, GoldenSetCassette.Entry> recorded = new LinkedHashMap<>();

        for (CapabilityGoldenSet.Case goldenCase : goldenSet.cases()) {
            CapabilityEvidenceInput evidence = goldenCase.toEvidence();
            List<CapabilityV2Dtos.BucketCandidate> existing = goldenCase.existingCandidates();
            GoldenSetCassette.Entry recordedEntry = mode == Mode.REPLAY ? cassette.cases().get(goldenCase.id()) : null;
            // Replay judges recorded answers against the candidates the model actually saw; recall is measured elsewhere.
            List<FunctionBucketCandidateService.FunctionCandidateMatch> candidates =
                    recordedEntry != null && recordedEntry.functionCandidateNames() != null
                            ? functionAgent.recordedCandidates(List.copyOf(recordedEntry.functionCandidateNames().values()))
                            : functionAgent.recall(evidence);
            FunctionCapabilityAttributionAgent.DeliveryGate gate = functionAgent.deliveryGate(evidence);

            KnowledgeBucketNormalizationAgent.KnowledgeDecision knowledge = null;
            FunctionCapabilityAttributionAgent.FunctionDecision function = null;
            CapabilityGoldenSetScorer.Stage pendingStage = CapabilityGoldenSetScorer.Stage.NOT_EVALUATED;
            List<String> errors = new ArrayList<>();

            switch (mode) {
                case DETERMINISTIC -> {
                    // Without a model only gate-blocked cases have a final answer; decide() makes no model call for them.
                    if (!gate.passed() || candidates.isEmpty()) {
                        function = functionAgent.decide(evidence, candidates, gate);
                    }
                }
                case LIVE -> {
                    try {
                        knowledge = knowledgeAgent.decide(evidence, existing);
                    } catch (CapabilityModelUnavailableException exception) {
                        errors.add(exception.getMessage());
                    }
                    try {
                        function = functionAgent.decide(evidence, candidates, gate);
                    } catch (CapabilityModelUnavailableException exception) {
                        errors.add(exception.getMessage());
                    }
                    if (!errors.isEmpty()) {
                        pendingStage = CapabilityGoldenSetScorer.Stage.MODEL_UNAVAILABLE;
                    }
                    recorded.put(goldenCase.id(), GoldenSetCassette.Entry.of(knowledge, function, candidates));
                }
                case REPLAY -> {
                    GoldenSetCassette.Entry entry = cassette.cases().get(goldenCase.id());
                    if (entry == null) {
                        errors.add("cassette 中没有该样例，请重新录制");
                        pendingStage = CapabilityGoldenSetScorer.Stage.NOT_EVALUATED;
                    } else {
                        knowledge = entry.knowledge() == null ? null
                                : knowledgeAgent.validate(existing, entry.knowledge(), "replay");
                        if (!gate.passed() || candidates.isEmpty()) {
                            // These paths never call the model, so they are re-run exactly.
                            function = functionAgent.decide(evidence, candidates, gate);
                        } else if (entry.function() != null) {
                            function = functionAgent.validate(candidates, entry.remapFunction(candidates), "replay");
                        }
                        // Otherwise the recording had no model answer for this case; it stays NOT_EVALUATED.
                    }
                }
            }

            results.add(new CapabilityGoldenSetScorer.CaseResult(
                    goldenCase.id(),
                    goldenCase.category(),
                    goldenCase.knowledge().mustReuse(),
                    CapabilityGoldenSetScorer.scoreKnowledge(goldenCase, knowledge, pendingStage),
                    CapabilityGoldenSetScorer.scoreFunction(goldenCase, gate, candidates, function, pendingStage),
                    errors.isEmpty() ? null : String.join("；", errors)
            ));
        }

        return new Report(
                CapabilityGoldenSetScorer.summarize(mode.name(), goldenSet.labelStatus(), results),
                results,
                mode == Mode.LIVE ? new GoldenSetCassette(goldenSet.version(), recorded) : null
        );
    }

    public record Report(
            CapabilityGoldenSetScorer.Summary summary,
            List<CapabilityGoldenSetScorer.CaseResult> cases,
            GoldenSetCassette cassette
    ) {
    }
}
