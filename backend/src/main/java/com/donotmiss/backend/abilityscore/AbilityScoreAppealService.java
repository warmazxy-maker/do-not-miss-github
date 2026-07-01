package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AbilityScoreAppealService {
    private final AbilityScoreAppealRepository appealRepository;
    private final AbilityScoreResultRepository resultRepository;

    public AbilityScoreAppealService(AbilityScoreAppealRepository appealRepository,
                                     AbilityScoreResultRepository resultRepository) {
        this.appealRepository = appealRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional
    public AbilityScoreAppealEntity createReplayRequest(String userId,
                                                        AbilityScoreResultEntity oldResult,
                                                        Long replacementAssessmentId,
                                                        String reason) {
        return appealRepository
                .findByScoreResultIdAndReplacementAssessmentIdAndNormalizedDimensionAndRequestType(
                        oldResult.getId(),
                        replacementAssessmentId,
                        oldResult.getNormalizedDimension(),
                        AbilityScoreAppealType.EVIDENCE_CHANGED_REPLAY
                )
                .orElseGet(() -> {
                    AbilityScoreAppealEntity appeal = new AbilityScoreAppealEntity();
                    appeal.setRequestId(UUID.randomUUID().toString());
                    appeal.setUserId(userId);
                    appeal.setRequestType(AbilityScoreAppealType.EVIDENCE_CHANGED_REPLAY);
                    appeal.setStatus(AbilityScoreAppealStatus.PENDING);
                    appeal.setScoreResultId(oldResult.getId());
                    appeal.setReplacementAssessmentId(replacementAssessmentId);
                    appeal.setNormalizedDimension(oldResult.getNormalizedDimension());
                    appeal.setReason(compact(reason, 1200));
                    return appealRepository.save(appeal);
                });
    }

    @Transactional
    public AbilityScoreAppealEntity createUserAppeal(String userId,
                                                     Long scoreResultId,
                                                     String reason,
                                                     String evidenceNote) {
        AbilityScoreResultEntity result = resultRepository.findById(scoreResultId)
                .filter(item -> item.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Score result not found: " + scoreResultId));
        boolean alreadyPending = appealRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .anyMatch(item -> item.getScoreResultId().equals(scoreResultId)
                        && item.getRequestType() == AbilityScoreAppealType.USER_APPEAL
                        && (item.getStatus() == AbilityScoreAppealStatus.PENDING
                        || item.getStatus() == AbilityScoreAppealStatus.IN_REVIEW));
        if (alreadyPending) {
            throw ApiException.badRequest("This score result already has a pending appeal");
        }

        AbilityScoreAppealEntity appeal = new AbilityScoreAppealEntity();
        appeal.setRequestId(UUID.randomUUID().toString());
        appeal.setUserId(userId);
        appeal.setRequestType(AbilityScoreAppealType.USER_APPEAL);
        appeal.setStatus(AbilityScoreAppealStatus.PENDING);
        appeal.setScoreResultId(result.getId());
        appeal.setNormalizedDimension(result.getNormalizedDimension());
        appeal.setReason(compact(reason, 1200));
        appeal.setEvidenceNote(compactOptional(evidenceNote, 2000));
        return appealRepository.save(appeal);
    }

    @Transactional(readOnly = true)
    public List<AbilityScoreAppealEntity> list(String userId) {
        return appealRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            throw ApiException.badRequest("Appeal reason must not be blank");
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String compactOptional(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
