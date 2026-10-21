package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.capabilityv2.CapabilityBucketType;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceInput;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceSourceKind;
import com.donotmiss.backend.capabilityv2.CapabilityV2Dtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Stage 16 golden set: fixed evidence samples with the ideal knowledge and function answer.
 *
 * <p>Each case carries its own "existing knowledge buckets" fixture, so evaluation never reads
 * or writes a real user's labels. Labels describe what should happen, not what the current
 * rules happen to do, so rule weaknesses show up as measured errors.</p>
 */
public record CapabilityGoldenSet(String version, String labelStatus, String notes, List<Case> cases) {
    public static final String RESOURCE = "capability-v2/golden-set.json";
    private static final String EVAL_USER = "golden-set-eval";

    public static CapabilityGoldenSet load(ObjectMapper objectMapper) {
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(stream, CapabilityGoldenSet.class);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取能力评测集：" + RESOURCE, exception);
        }
    }

    public record Case(
            String id,
            String category,
            CapabilityEvidenceSourceKind sourceKind,
            String title,
            String description,
            String reflection,
            List<String> facts,
            List<ExistingBucket> existingKnowledge,
            KnowledgeExpectation knowledge,
            FunctionExpectation function
    ) {
        public CapabilityEvidenceInput toEvidence() {
            AbilityEvidenceSourceType sourceType = switch (sourceKind) {
                case ACTIVITY_COMPLETION -> AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD;
                case CHALLENGE_COMPLETION -> AbilityEvidenceSourceType.SELF_REPORT;
                case COACH_LOG -> AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD;
                case RESUME_INTERVIEW -> AbilityEvidenceSourceType.RESUME_INTERVIEW;
            };
            return new CapabilityEvidenceInput(
                    "capability-evidence-v2",
                    "golden:" + id,
                    "golden:" + id,
                    sourceKind,
                    EVAL_USER,
                    id,
                    sourceType.name(),
                    sourceType.credibilityPrior(),
                    title,
                    description,
                    reflection,
                    "{}",
                    "{}",
                    facts == null ? List.of() : facts,
                    Instant.parse("2026-10-01T00:00:00Z"),
                    true,
                    "golden set",
                    sha256(id + title)
            );
        }

        /** Synthetic negative ids keep the fixture apart from any real bucket id. */
        public List<CapabilityV2Dtos.BucketCandidate> existingCandidates() {
            List<CapabilityV2Dtos.BucketCandidate> candidates = new ArrayList<>();
            List<ExistingBucket> buckets = existingKnowledge == null ? List.of() : existingKnowledge;
            for (int index = 0; index < buckets.size(); index++) {
                ExistingBucket bucket = buckets.get(index);
                candidates.add(new CapabilityV2Dtos.BucketCandidate(
                        -(index + 1L), CapabilityBucketType.KNOWLEDGE, bucket.name(), null, null, null,
                        bucket.aliases() == null ? List.of() : bucket.aliases()));
            }
            return candidates;
        }
    }

    public record ExistingBucket(String name, List<String> aliases) {
    }

    public record ExpectedBucket(String name, List<String> alternatives) {
        public List<String> names() {
            List<String> names = new ArrayList<>();
            names.add(name);
            if (alternatives != null) {
                names.addAll(alternatives);
            }
            return names;
        }
    }

    public record KnowledgeExpectation(
            List<ExpectedBucket> expected,
            List<String> optional,
            List<String> mustReuse,
            List<String> forbidden
    ) {
        public KnowledgeExpectation {
            expected = expected == null ? List.of() : expected;
            optional = optional == null ? List.of() : optional;
            mustReuse = mustReuse == null ? List.of() : mustReuse;
            forbidden = forbidden == null ? List.of() : forbidden;
        }
    }

    public record FunctionExpectation(
            boolean gate,
            List<String> primary,
            List<String> acceptable,
            boolean expectNone
    ) {
        public FunctionExpectation {
            primary = primary == null ? List.of() : primary;
            acceptable = acceptable == null ? List.of() : acceptable;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
