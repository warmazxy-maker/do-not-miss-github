package com.donotmiss.backend.auth;

import com.donotmiss.backend.common.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private static final String SESSION_PREFIX = "auth:session:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByUsername(username)) {
            throw ApiException.badRequest("用户名已被使用");
        }
        if (userRepository.existsByEmail(email)) {
            throw ApiException.badRequest("邮箱已被注册");
        }

        UserEntity user = new UserEntity();
        user.setPublicUserId("user-" + UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.fromText(request.role()));
        return issueToken(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        UserEntity user = findByAccount(request.account())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误"));

        if (!matchesPassword(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        return issueToken(user);
    }

    public void logout(String token) {
        redisTemplate.delete(SESSION_PREFIX + token);
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse me(String publicUserId) {
        return userRepository.findByPublicUserId(publicUserId)
                .map(AuthDtos.UserResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户不存在或登录已失效"));
    }

    public String resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }

        String publicUserId = redisTemplate.opsForValue().get(SESSION_PREFIX + token);

        if (publicUserId == null || publicUserId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }

        return publicUserId;
    }

    private Optional<UserEntity> findByAccount(String account) {
        String normalized = account.trim();

        if (normalized.contains("@")) {
            return userRepository.findByEmail(normalized.toLowerCase());
        }

        return userRepository.findByUsername(normalized);
    }

    private AuthDtos.AuthResponse issueToken(UserEntity user) {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(SESSION_PREFIX + token, user.getPublicUserId(), SESSION_TTL);
        return new AuthDtos.AuthResponse(token, Instant.now().plus(SESSION_TTL), AuthDtos.UserResponse.from(user));
    }

    private boolean matchesPassword(String rawPassword, String storedHash) {
        if (storedHash.startsWith("{plain}")) {
            return storedHash.substring("{plain}".length()).equals(rawPassword);
        }

        return passwordEncoder.matches(rawPassword, storedHash);
    }
}
