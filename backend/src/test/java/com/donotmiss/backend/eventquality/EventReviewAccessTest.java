package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.access.AccessService;
import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Publisher-or-reviewer rule for quality report and re-analysis (D0, moved onto permissions in D1). */
@ExtendWith(MockitoExtension.class)
class EventReviewAccessTest {
    @Mock
    private AccessService accessService;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private HttpServletRequest request;

    @Test
    void reviewerMayActOnAnyEventWithoutLookingItUp() {
        when(accessService.current(request)).thenReturn(principal("op-1", "OPERATOR", Permission.EVENT_REVIEW));

        assertDoesNotThrow(() -> access().requirePublisherOrReviewer(request, 58L));
        verify(eventRepository, never()).findById(58L);
    }

    @Test
    void publisherMayActOnOwnEventOnly() {
        EventEntity event = EventEntity.detached(58L);
        event.setCreatedByUserId("demo-social");
        when(eventRepository.findById(58L)).thenReturn(Optional.of(event));

        when(accessService.current(request)).thenReturn(principal("demo-social", "SOCIAL", Permission.EVENT_PUBLISH));
        assertDoesNotThrow(() -> access().requirePublisherOrReviewer(request, 58L));

        when(accessService.current(request)).thenReturn(principal("other-org", "SOCIAL", Permission.EVENT_PUBLISH));
        ApiException error = assertThrows(ApiException.class, () -> access().requirePublisherOrReviewer(request, 58L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void studentWhoSomehowCreatedTheEventStillCannotReanalyse() {
        EventEntity event = EventEntity.detached(58L);
        event.setCreatedByUserId("demo-student");
        when(eventRepository.findById(58L)).thenReturn(Optional.of(event));
        when(accessService.current(request)).thenReturn(principal("demo-student", "STUDENT", Permission.STUDENT_FEATURES));

        ApiException error = assertThrows(ApiException.class, () -> access().requirePublisherOrReviewer(request, 58L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void anonymousCallerIsRejectedBeforeAnyLookup() {
        when(accessService.current(request)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "请先登录"));

        ApiException error = assertThrows(ApiException.class, () -> access().requirePublisherOrReviewer(request, 58L));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        verify(eventRepository, never()).findById(58L);
    }

    private EventReviewAccess access() {
        return new EventReviewAccess(accessService, eventRepository);
    }

    private static AccessService.Principal principal(String userId, String role, Permission... permissions) {
        return new AccessService.Principal(userId, role, Set.of(permissions));
    }
}
