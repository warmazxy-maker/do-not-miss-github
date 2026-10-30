package com.donotmiss.backend.access;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.common.ApiException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * admin_zero creates operator accounts (D3, G4). Accounts are created as operators from the start
 * instead of turning an existing student or organisation account into one: one role per user (D1),
 * so converting would take that person's other features away. Administrator roles still cannot be
 * granted through any endpoint.
 */
@Service
public class OperatorAccountService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,30}");
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public OperatorAccountService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record OperatorAccount(String publicUserId, String username, Instant createdAt) {
    }

    /** The initial password is returned exactly once and stored only as a hash. */
    public record CreatedOperator(String publicUserId, String username, String initialPassword) {
    }

    @Transactional(readOnly = true)
    public List<OperatorAccount> list() {
        return userRepository.findByRoleOrderByCreatedAtAsc(RoleEntity.OPERATOR).stream()
                .map(user -> new OperatorAccount(user.getPublicUserId(), user.getUsername(), user.getCreatedAt()))
                .toList();
    }

    @Transactional
    public CreatedOperator create(String requestedUsername) {
        String username = requestedUsername == null ? "" : requestedUsername.trim();
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("用户名只能是 3～30 位字母、数字或下划线");
        }
        if (userRepository.existsByUsername(username)) {
            throw ApiException.badRequest("用户名已被使用");
        }
        // Operators sign in with the username; the address only satisfies the unique, non-null column.
        String email = username.toLowerCase(Locale.ROOT) + "@operator.do-not-miss.local";
        if (userRepository.existsByEmail(email)) {
            throw ApiException.badRequest("用户名已被使用");
        }
        String password = randomPassword();
        UserEntity user = new UserEntity();
        user.setPublicUserId("operator-" + UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(RoleEntity.OPERATOR);
        UserEntity saved = userRepository.save(user);
        return new CreatedOperator(saved.getPublicUserId(), saved.getUsername(), password);
    }

    private String randomPassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return password.toString();
    }
}
