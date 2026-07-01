package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ability-scoring")
public class AbilityScoringController {
    private final EvidenceAssessmentAgentService assessmentAgentService;
    private final UserAbilityStateRepository stateRepository;
    private final AbilityScoreResultRepository resultRepository;
    private final AbilityScoreAppealService appealService;
    private final AbilityHacClusteringService clusteringService;
    private final AbilityEvidenceTimelineService evidenceTimelineService;
    private final CurrentUser currentUser;

    public AbilityScoringController(EvidenceAssessmentAgentService assessmentAgentService,
                                    UserAbilityStateRepository stateRepository,
                                    AbilityScoreResultRepository resultRepository,
                                    AbilityScoreAppealService appealService,
                                    AbilityHacClusteringService clusteringService,
                                    AbilityEvidenceTimelineService evidenceTimelineService,
                                    CurrentUser currentUser) {
        this.assessmentAgentService = assessmentAgentService;
        this.stateRepository = stateRepository;
        this.resultRepository = resultRepository;
        this.appealService = appealService;
        this.clusteringService = clusteringService;
        this.evidenceTimelineService = evidenceTimelineService;
        this.currentUser = currentUser;
    }

    @PostMapping("/records/{recordId}/assess")
    public EvidenceAssessmentDtos.AssessmentResponse assess(@PathVariable Long recordId,
                                                            HttpServletRequest request) {
        return assessmentAgentService.assessRecord(recordId, currentUser.id(request));
    }

    @GetMapping("/states")
    public List<AbilityStateResponse> states(HttpServletRequest request) {
        return stateRepository.findByUserIdOrderByAbilityScoreDesc(currentUser.id(request)).stream()
                .map(AbilityStateResponse::from)
                .toList();
    }

    @GetMapping("/clusters")
    public List<AbilityClusterModels.AbilityClusterResponse> clusters(HttpServletRequest request) {
        return clusteringService.clusterUser(currentUser.id(request));
    }

    @GetMapping("/states/{stateId}/evidences")
    public AbilityEvidenceTimelineService.AbilityEvidenceTimelineResponse evidences(
            @PathVariable Long stateId,
            HttpServletRequest request
    ) {
        return evidenceTimelineService.timeline(currentUser.id(request), stateId);
    }

    @GetMapping("/results")
    public List<AbilityScoreResultResponse> results(HttpServletRequest request) {
        return resultRepository.findTop50ByUserIdOrderByCreatedAtDesc(currentUser.id(request)).stream()
                .map(AbilityScoreResultResponse::from)
                .toList();
    }

    @PostMapping("/results/{resultId}/appeals")
    public AbilityScoreAppealResponse createAppeal(@PathVariable Long resultId,
                                                   @Valid @RequestBody CreateAppealRequest body,
                                                   HttpServletRequest request) {
        return AbilityScoreAppealResponse.from(appealService.createUserAppeal(
                currentUser.id(request),
                resultId,
                body.reason(),
                body.evidenceNote()
        ));
    }

    @GetMapping("/appeals")
    public List<AbilityScoreAppealResponse> appeals(HttpServletRequest request) {
        return appealService.list(currentUser.id(request)).stream()
                .map(AbilityScoreAppealResponse::from)
                .toList();
    }

    public record AbilityStateResponse(
            Long id,
            String dimension,
            String normalizedDimension,
            java.math.BigDecimal experienceScore,
            java.math.BigDecimal abilityScore,
            java.math.BigDecimal abilityUncertainty,
            String rank
    ) {
        static AbilityStateResponse from(UserAbilityStateEntity entity) {
            return new AbilityStateResponse(
                    entity.getId(),
                    entity.getDimensionName(),
                    entity.getNormalizedDimension(),
                    entity.getExperienceScore(),
                    entity.getAbilityScore(),
                    entity.getAbilityUncertainty(),
                    entity.getRankName()
            );
        }
    }

    public record AbilityScoreResultResponse(
            Long id,
            Long achievementRecordId,
            String dimension,
            AbilityScoreResultStatus status,
            java.math.BigDecimal verifiedExperienceGain,
            java.math.BigDecimal oldAbilityScore,
            java.math.BigDecimal newAbilityScore,
            java.math.BigDecimal newAbilityUncertainty,
            String scoringRuleVersion,
            Long supersedesResultId,
            Instant createdAt
    ) {
        static AbilityScoreResultResponse from(AbilityScoreResultEntity entity) {
            return new AbilityScoreResultResponse(
                    entity.getId(),
                    entity.getAchievementRecordId(),
                    entity.getDimensionName(),
                    entity.getStatus(),
                    entity.getVerifiedExperienceGain(),
                    entity.getOldAbilityScore(),
                    entity.getNewAbilityScore(),
                    entity.getNewAbilityUncertainty(),
                    entity.getScoringRuleVersion(),
                    entity.getSupersedesResultId(),
                    entity.getCreatedAt()
            );
        }
    }

    public record CreateAppealRequest(
            @NotBlank
            @Size(max = 1200)
            String reason,
            @Size(max = 2000)
            String evidenceNote
    ) {
    }

    public record AbilityScoreAppealResponse(
            Long id,
            String requestId,
            AbilityScoreAppealType requestType,
            AbilityScoreAppealStatus status,
            Long scoreResultId,
            Long replacementAssessmentId,
            String normalizedDimension,
            String reason,
            String evidenceNote,
            String resolution,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt
    ) {
        static AbilityScoreAppealResponse from(AbilityScoreAppealEntity entity) {
            return new AbilityScoreAppealResponse(
                    entity.getId(),
                    entity.getRequestId(),
                    entity.getRequestType(),
                    entity.getStatus(),
                    entity.getScoreResultId(),
                    entity.getReplacementAssessmentId(),
                    entity.getNormalizedDimension(),
                    entity.getReason(),
                    entity.getEvidenceNote(),
                    entity.getResolution(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    entity.getResolvedAt()
            );
        }
    }
}
