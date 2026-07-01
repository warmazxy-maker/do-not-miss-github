package com.donotmiss.backend.abilityscore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceAssessmentPersistenceServiceTest {
    @Mock
    private AbilityAssessmentJobRepository jobRepository;

    @Mock
    private AbilityEvidenceAssessmentRepository assessmentRepository;

    @Mock
    private AbilityEvidenceDimensionRepository dimensionRepository;

    @Test
    void identicalContentAcrossDifferentRecordsIsHeldForDuplicateReview() {
        AbilityAssessmentJobEntity previous = new AbilityAssessmentJobEntity();
        setId(previous, 9L);
        previous.setUserId("student-1");
        previous.setAchievementRecordId(100L);
        previous.setContentFingerprint("same-content");

        when(jobRepository.findByAchievementRecordIdAndEvidenceHashAndPromptVersionAndRubricVersion(
                101L,
                "evidence-hash",
                "prompt-v2",
                "rubric-v2"
        )).thenReturn(Optional.empty());
        when(jobRepository
                .findFirstByUserIdAndContentFingerprintAndAchievementRecordIdNotOrderByCreatedAtDesc(
                        "student-1",
                        "same-content",
                        101L
                )).thenReturn(Optional.of(previous));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceAssessmentPersistenceService service = new EvidenceAssessmentPersistenceService(
                jobRepository,
                assessmentRepository,
                dimensionRepository
        );
        AbilityAssessmentJobEntity created = service.findOrCreateJob(
                "student-1",
                101L,
                "evidence-hash",
                "same-content",
                "prompt-v2",
                "rubric-v2",
                "{}"
        );

        assertEquals(AbilityAssessmentFairnessStatus.DUPLICATE_REVIEW, created.getFairnessStatus());
        assertEquals(AbilityAssessmentJobStatus.REVIEW_REQUIRED, created.getStatus());
        assertEquals(9L, created.getDuplicateOfJobId());
    }

    private void setId(AbilityAssessmentJobEntity entity, Long id) {
        try {
            var field = AbilityAssessmentJobEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
