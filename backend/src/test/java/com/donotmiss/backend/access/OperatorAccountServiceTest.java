package com.donotmiss.backend.access;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperatorAccountServiceTest {
    private UserRepository userRepository;
    private OperatorAccountService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new OperatorAccountService(userRepository);
    }

    @Test
    void createsAnOperatorAndStoresOnlyTheHash() {
        OperatorAccountService.CreatedOperator created = service.create("  review_01 ");

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(saved.capture());
        UserEntity user = saved.getValue();
        assertEquals("review_01", user.getUsername());
        assertEquals(RoleEntity.OPERATOR, user.getRole());
        assertEquals(16, created.initialPassword().length());
        assertNotEquals(created.initialPassword(), user.getPasswordHash());
        assertTrue(new BCryptPasswordEncoder().matches(created.initialPassword(), user.getPasswordHash()));
    }

    @Test
    void rejectsBadOrTakenUsernames() {
        assertThrows(ApiException.class, () -> service.create("ab"));
        assertThrows(ApiException.class, () -> service.create("有中文"));
        when(userRepository.existsByUsername("taken_name")).thenReturn(true);
        assertThrows(ApiException.class, () -> service.create("taken_name"));
        verify(userRepository, never()).save(any());
    }
}
