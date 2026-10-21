package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityScoreCalculator;
import com.donotmiss.backend.abilityscore.UserAbilityStateEntity;
import com.donotmiss.backend.abilityscore.UserAbilityStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyAbilityMigrationPreviewServiceTest {
    @Mock
    private UserAbilityStateRepository legacyStateRepository;

    @Test
    void combinesLegacyJavaFragmentsIntoOneCoarseReadOnlyPreview() {
        when(legacyStateRepository.findByUserIdOrderByAbilityScoreDesc("student-1"))
                .thenReturn(List.of(
                        state("Java 编程基础", "java-programming-basics", "2.0000", "1.2000", "0.8000"),
                        state("Java 集合应用", "java-collections", "1.5000", "0.8000", "0.9000")
                ));

        LegacyAbilityMigrationPreviewService.MigrationPreview preview = service().previewForUser(" student-1 ");

        assertTrue(preview.readOnly());
        assertEquals("LEGACY_RULE_PREVIEW_V1", preview.mode());
        assertEquals(2, preview.summary().legacyStateCount());
        assertEquals(1, preview.knowledgeBuckets().size());
        LegacyAbilityMigrationPreviewService.KnowledgeBucketPreview java = preview.knowledgeBuckets().getFirst();
        assertEquals("Java", java.bucketName());
        assertEquals(new BigDecimal("2.5250"), java.suggestedExperienceScore());
        assertFalse(java.requiresManualReview());
        assertEquals(BigDecimal.ONE.setScale(4), java.mappingConfidence());
        assertEquals(List.of("Java 编程基础", "Java 集合应用"), java.legacyDimensions());
        verify(legacyStateRepository).findByUserIdOrderByAbilityScoreDesc("student-1");
        verify(legacyStateRepository, never()).save(any());
    }

    @Test
    void preservesUnknownLegacyNameAndRequestsHumanReviewInsteadOfGuessing() {
        when(legacyStateRepository.findByUserIdOrderByAbilityScoreDesc("student-2"))
                .thenReturn(List.of(state("量子计算能力", "quantum-computing", "1.2000", "0.6000", "0.8500")));

        LegacyAbilityMigrationPreviewService.MigrationPreview preview = service().previewForUser("student-2");

        assertEquals(1, preview.knowledgeBuckets().size());
        LegacyAbilityMigrationPreviewService.KnowledgeBucketPreview bucket = preview.knowledgeBuckets().getFirst();
        assertEquals("量子计算", bucket.bucketName());
        assertTrue(bucket.requiresManualReview());
        assertEquals(new BigDecimal("0.5800"), bucket.mappingConfidence());
        assertEquals(1, preview.manualReviewItems().size());
        verify(legacyStateRepository, never()).save(any());
    }

    @Test
    void returnsAnEmptyPreviewForANewUserWithoutOldStates() {
        when(legacyStateRepository.findByUserIdOrderByAbilityScoreDesc("new-user"))
                .thenReturn(List.of());

        LegacyAbilityMigrationPreviewService.MigrationPreview preview = service().previewForUser("new-user");

        assertTrue(preview.readOnly());
        assertEquals(0, preview.summary().legacyStateCount());
        assertTrue(preview.knowledgeBuckets().isEmpty());
        assertTrue(preview.manualReviewItems().isEmpty());
    }

    private LegacyAbilityMigrationPreviewService service() {
        return new LegacyAbilityMigrationPreviewService(legacyStateRepository, new AbilityScoreCalculator());
    }

    private UserAbilityStateEntity state(String name,
                                          String normalized,
                                          String experience,
                                          String score,
                                          String uncertainty) {
        UserAbilityStateEntity state = new UserAbilityStateEntity();
        state.setDimensionName(name);
        state.setNormalizedDimension(normalized);
        state.setExperienceScore(new BigDecimal(experience));
        state.setAbilityScore(new BigDecimal(score));
        state.setAbilityUncertainty(new BigDecimal(uncertainty));
        return state;
    }
}
