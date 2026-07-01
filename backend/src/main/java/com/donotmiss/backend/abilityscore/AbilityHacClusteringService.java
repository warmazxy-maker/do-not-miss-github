package com.donotmiss.backend.abilityscore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class AbilityHacClusteringService {
    static final String ALGORITHM_VERSION = "hac-average-v1.1-dynamic-anchor";

    private final UserAbilityStateRepository stateRepository;
    private final AbilityFeatureVectorizer vectorizer;
    private final AbilityDynamicAnchorRegistryService anchorRegistry;
    private final double mergeThreshold;

    public AbilityHacClusteringService(UserAbilityStateRepository stateRepository,
                                       AbilityFeatureVectorizer vectorizer,
                                       AbilityDynamicAnchorRegistryService anchorRegistry,
                                       @Value("${app.ability-clustering.merge-threshold:0.62}") double mergeThreshold) {
        this.stateRepository = stateRepository;
        this.vectorizer = vectorizer;
        this.anchorRegistry = anchorRegistry;
        this.mergeThreshold = Math.max(0.0, Math.min(1.0, mergeThreshold));
    }

    @Transactional
    public List<AbilityClusterModels.AbilityClusterResponse> clusterUser(String userId) {
        return cluster(stateRepository.findByUserIdOrderByAbilityScoreDesc(userId));
    }

    List<AbilityClusterModels.AbilityClusterResponse> cluster(List<UserAbilityStateEntity> states) {
        return cluster(states, List.of());
    }

    List<AbilityClusterModels.AbilityClusterResponse> cluster(
            List<UserAbilityStateEntity> states,
            List<AbilityFeatureVectorizer.AnchorDefinition> dynamicAnchors
    ) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        List<AbilityFeatureVectorizer.AnchorDefinition> activeAnchors = dynamicAnchors == null || dynamicAnchors.isEmpty()
                ? safeApprovedAnchors()
                : dynamicAnchors;

        List<Node> nodes = states.stream()
                .map(state -> new Node(
                        new ArrayList<>(List.of(state)),
                        new ArrayList<>(List.of(vectorizer.vectorize(
                                state.getDimensionName() + " " + state.getNormalizedDimension(),
                                activeAnchors
                        )))
                ))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        while (nodes.size() > 1) {
            MergeCandidate best = bestCandidate(nodes);
            if (best == null || best.similarity() < mergeThreshold) {
                break;
            }
            Node merged = nodes.get(best.left()).merge(nodes.get(best.right()));
            nodes.remove(best.right());
            nodes.remove(best.left());
            nodes.add(merged);
        }

        return nodes.stream()
                .map(node -> toResponse(node, activeAnchors))
                .sorted(Comparator
                        .comparing(AbilityClusterModels.AbilityClusterResponse::abilityScore).reversed()
                        .thenComparing(
                                AbilityClusterModels.AbilityClusterResponse::experienceScore,
                                Comparator.reverseOrder()
                        ))
                .toList();
    }

    private List<AbilityFeatureVectorizer.AnchorDefinition> safeApprovedAnchors() {
        if (anchorRegistry == null) {
            return List.of();
        }
        List<AbilityFeatureVectorizer.AnchorDefinition> anchors = anchorRegistry.approvedAnchors();
        return anchors == null ? List.of() : anchors;
    }

    private MergeCandidate bestCandidate(List<Node> nodes) {
        MergeCandidate best = null;
        for (int left = 0; left < nodes.size(); left++) {
            for (int right = left + 1; right < nodes.size(); right++) {
                double similarity = averageLinkage(nodes.get(left), nodes.get(right));
                if (best == null || similarity > best.similarity()) {
                    best = new MergeCandidate(left, right, similarity);
                }
            }
        }
        return best;
    }

    private double averageLinkage(Node left, Node right) {
        double sum = 0.0;
        int comparisons = 0;
        for (AbilityFeatureVectorizer.FeatureVector leftVector : left.vectors()) {
            for (AbilityFeatureVectorizer.FeatureVector rightVector : right.vectors()) {
                sum += vectorizer.similarity(leftVector, rightVector);
                comparisons++;
            }
        }
        return comparisons == 0 ? 0.0 : sum / comparisons;
    }

    private AbilityClusterModels.AbilityClusterResponse toResponse(
            Node node,
            List<AbilityFeatureVectorizer.AnchorDefinition> dynamicAnchors
    ) {
        List<UserAbilityStateEntity> members = node.members().stream()
                .sorted(Comparator.comparing(UserAbilityStateEntity::getAbilityScore).reversed())
                .toList();
        Set<String> anchors = vectorizer.sharedAnchors(node.vectors());
        Optional<AbilityFeatureVectorizer.AnchorDefinition> discovered = discoverAnchorIfNeeded(members, anchors, node);
        discovered.ifPresent(anchor -> anchors.add(anchor.key()));
        String name = discovered
                .map(AbilityFeatureVectorizer.AnchorDefinition::displayName)
                .orElseGet(() -> vectorizer.preferredName(anchors, members, dynamicAnchors));
        String anchorKey = discovered
                .map(AbilityFeatureVectorizer.AnchorDefinition::key)
                .orElseGet(() -> anchors.stream().findFirst().orElse(""));
        String anchorSource = discovered
                .map(AbilityFeatureVectorizer.AnchorDefinition::source)
                .orElseGet(() -> anchorSource(anchorKey, dynamicAnchors));
        String anchorStatus = discovered
                .map(AbilityFeatureVectorizer.AnchorDefinition::status)
                .orElseGet(() -> anchorStatus(anchorKey, dynamicAnchors));

        double totalWeight = 0.0;
        double weightedAbility = 0.0;
        double weightedUncertainty = 0.0;
        List<BigDecimal> memberExperiences = new ArrayList<>();
        for (UserAbilityStateEntity member : members) {
            double confidence = Math.max(0.15, 1.0 - member.getAbilityUncertainty().doubleValue());
            double evidenceWeight = Math.max(1.0, Math.log1p(member.getExperienceScore().doubleValue()));
            double weight = confidence * evidenceWeight;
            totalWeight += weight;
            weightedAbility += member.getAbilityScore().doubleValue() * weight;
            weightedUncertainty += member.getAbilityUncertainty().doubleValue() * weight;
            memberExperiences.add(member.getExperienceScore());
        }
        memberExperiences.sort(Comparator.reverseOrder());
        BigDecimal experience = memberExperiences.isEmpty()
                ? BigDecimal.ZERO
                : memberExperiences.getFirst().add(
                        memberExperiences.stream()
                                .skip(1)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .multiply(new BigDecimal("0.35"))
                );

        BigDecimal abilityScore = decimal(totalWeight == 0.0 ? 0.0 : weightedAbility / totalWeight);
        BigDecimal uncertainty = decimal(totalWeight == 0.0 ? 1.0 : weightedUncertainty / totalWeight);
        AbilityFeatureVectorizer.FeatureVector centroid = vectorizer.vectorize(name, dynamicAnchors);
        List<AbilityClusterModels.AbilityClusterMemberResponse> memberResponses = members.stream()
                .map(member -> new AbilityClusterModels.AbilityClusterMemberResponse(
                        member.getId(),
                        member.getDimensionName(),
                        member.getNormalizedDimension(),
                        member.getExperienceScore(),
                        member.getAbilityScore(),
                        member.getAbilityUncertainty(),
                        member.getRankName(),
                        decimal(vectorizer.similarity(
                                centroid,
                                vectorizer.vectorize(member.getDimensionName() + " " + member.getNormalizedDimension(), dynamicAnchors)
                        ))
                ))
                .toList();

        return new AbilityClusterModels.AbilityClusterResponse(
                clusterKey(members),
                name,
                abilityScore,
                experience.setScale(4, RoundingMode.HALF_UP),
                uncertainty,
                rankFor(abilityScore),
                members.size(),
                ALGORITHM_VERSION,
                memberResponses,
                anchorKey,
                anchorSource,
                anchorStatus
        );
    }

    private Optional<AbilityFeatureVectorizer.AnchorDefinition> discoverAnchorIfNeeded(
            List<UserAbilityStateEntity> members,
            Set<String> anchors,
            Node node
    ) {
        if (anchorRegistry == null || !anchors.isEmpty() || members.size() < anchorRegistry.minMembers()) {
            return Optional.empty();
        }
        Optional<AbilityFeatureVectorizer.AnchorDefinition> promoted = anchorRegistry.promoteStableCluster(
                members,
                internalSimilarity(node)
        );
        return promoted == null ? Optional.empty() : promoted;
    }

    private double internalSimilarity(Node node) {
        if (node.vectors().size() < 2) {
            return 1.0;
        }
        double sum = 0.0;
        int comparisons = 0;
        for (int left = 0; left < node.vectors().size(); left++) {
            for (int right = left + 1; right < node.vectors().size(); right++) {
                sum += vectorizer.similarity(node.vectors().get(left), node.vectors().get(right));
                comparisons++;
            }
        }
        return comparisons == 0 ? 0.0 : sum / comparisons;
    }

    private String anchorSource(String anchorKey, List<AbilityFeatureVectorizer.AnchorDefinition> dynamicAnchors) {
        if (anchorKey == null || anchorKey.isBlank()) {
            return "";
        }
        return dynamicAnchors.stream()
                .filter(anchor -> anchor.key().equals(anchorKey))
                .map(AbilityFeatureVectorizer.AnchorDefinition::source)
                .findFirst()
                .orElse("STATIC");
    }

    private String anchorStatus(String anchorKey, List<AbilityFeatureVectorizer.AnchorDefinition> dynamicAnchors) {
        if (anchorKey == null || anchorKey.isBlank()) {
            return "";
        }
        return dynamicAnchors.stream()
                .filter(anchor -> anchor.key().equals(anchorKey))
                .map(AbilityFeatureVectorizer.AnchorDefinition::status)
                .findFirst()
                .orElse("APPROVED");
    }

    private String rankFor(BigDecimal score) {
        double value = score.doubleValue();
        if (value >= 85) return "EXPERT";
        if (value >= 70) return "ADVANCED";
        if (value >= 50) return "PROFICIENT";
        if (value >= 25) return "DEVELOPING";
        if (value > 0) return "FOUNDATION";
        return "UNRATED";
    }

    private String clusterKey(List<UserAbilityStateEntity> members) {
        String source = members.stream()
                .map(UserAbilityStateEntity::getNormalizedDimension)
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("empty");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "cluster-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.max(0.0, Math.min(100.0, value)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private record Node(
            List<UserAbilityStateEntity> members,
            List<AbilityFeatureVectorizer.FeatureVector> vectors
    ) {
        Node merge(Node other) {
            List<UserAbilityStateEntity> mergedMembers = new ArrayList<>(members);
            mergedMembers.addAll(other.members);
            List<AbilityFeatureVectorizer.FeatureVector> mergedVectors = new ArrayList<>(vectors);
            mergedVectors.addAll(other.vectors);
            return new Node(mergedMembers, mergedVectors);
        }
    }

    private record MergeCandidate(int left, int right, double similarity) {
    }
}
