package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbilityScoreAppealServiceTest {
    @Mock
    private AbilityScoreAppealRepository appealRepository;

    @Mock
    private AbilityScoreResultRepository resultRepository;

    @Test
    void createsOnePendingAppealOwnedByCurrentUser() {
        AbilityScoreResultEntity result = scoreResult(7L, "student-1", "java-backend");
        when(resultRepository.findById(7L)).thenReturn(Optional.of(result));
        when(appealRepository.findByUserIdOrderByCreatedAtDesc("student-1")).thenReturn(List.of());
        when(appealRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AbilityScoreAppealService service = new AbilityScoreAppealService(
                appealRepository,
                resultRepository
        );
        service.createUserAppeal("student-1", 7L, "The contribution evidence was incomplete.", "");

        ArgumentCaptor<AbilityScoreAppealEntity> captor =
                ArgumentCaptor.forClass(AbilityScoreAppealEntity.class);
        org.mockito.Mockito.verify(appealRepository).save(captor.capture());
        assertEquals(AbilityScoreAppealType.USER_APPEAL, captor.getValue().getRequestType());
        assertEquals(AbilityScoreAppealStatus.PENDING, captor.getValue().getStatus());
        assertEquals("java-backend", captor.getValue().getNormalizedDimension());
    }

    @Test
    void rejectsAnotherPendingAppealForSameResult() {
        AbilityScoreResultEntity result = scoreResult(7L, "student-1", "java-backend");
        AbilityScoreAppealEntity pending = new AbilityScoreAppealEntity();
        pending.setScoreResultId(7L);
        pending.setRequestType(AbilityScoreAppealType.USER_APPEAL);
        pending.setStatus(AbilityScoreAppealStatus.PENDING);

        when(resultRepository.findById(7L)).thenReturn(Optional.of(result));
        when(appealRepository.findByUserIdOrderByCreatedAtDesc("student-1"))
                .thenReturn(List.of(pending));

        AbilityScoreAppealService service = new AbilityScoreAppealService(
                appealRepository,
                resultRepository
        );

        assertThrows(
                ApiException.class,
                () -> service.createUserAppeal("student-1", 7L, "Please review it again.", null)
        );
    }

    private AbilityScoreResultEntity scoreResult(Long id, String userId, String dimension) {
        AbilityScoreResultEntity result = new AbilityScoreResultEntity();
        result.setUserId(userId);
        result.setNormalizedDimension(dimension);
        try {
            var field = AbilityScoreResultEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(result, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return result;
    }
}
