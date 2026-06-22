package com.donotmiss.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class AuthDtos {
    public record RegisterRequest(
            @NotBlank @Size(min = 2, max = 60) String username,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(min = 6, max = 72) String password,
            String role
    ) {
    }

    public record LoginRequest(
            @NotBlank String account,
            @NotBlank String password
    ) {
    }

    public record UserResponse(
            String userId,
            String username,
            String email,
            String role,
            Instant createdAt
    ) {
        public static UserResponse from(UserEntity user) {
            return new UserResponse(
                    user.getPublicUserId(),
                    user.getUsername(),
                    user.getEmail(),
                    user.getRole().name(),
                    user.getCreatedAt()
            );
        }
    }

    public record AuthResponse(
            String token,
            Instant expiresAt,
            UserResponse user
    ) {
    }
}
