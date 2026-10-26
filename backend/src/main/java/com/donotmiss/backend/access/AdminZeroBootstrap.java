package com.donotmiss.backend.access;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the super administrator {@code admin_zero} at startup. This is the only way an
 * administrator comes into existence for now: there is no endpoint that grants admin roles.
 *
 * <p>The password comes from configuration ({@code ADMIN_ZERO_PASSWORD} in the local, uncommitted
 * {@code .env}), never from a migration: migrations live in a public repository. Without the setting
 * nothing is created. An existing account keeps its password; only its role is enforced.</p>
 */
@Component
public class AdminZeroBootstrap implements ApplicationRunner {
    static final String USERNAME = "admin_zero";
    static final String PUBLIC_ID = "admin-zero";
    private static final Logger log = LoggerFactory.getLogger(AdminZeroBootstrap.class);

    private final UserRepository userRepository;
    private final String password;
    private final String email;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminZeroBootstrap(UserRepository userRepository,
                              @Value("${app.admin-zero.password:}") String password,
                              @Value("${app.admin-zero.email:admin_zero@do-not-miss.local}") String email) {
        this.userRepository = userRepository;
        this.password = password;
        this.email = email;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserEntity existing = userRepository.findByUsername(USERNAME).orElse(null);
        if (existing != null) {
            if (!RoleEntity.ADMIN_ZERO.equals(existing.getRole())) {
                existing.setRole(RoleEntity.ADMIN_ZERO);
                log.warn("Account {} did not have role {}; restored.", USERNAME, RoleEntity.ADMIN_ZERO);
            }
            return;
        }
        if (password == null || password.isBlank()) {
            log.info("ADMIN_ZERO_PASSWORD not set; super administrator account not created.");
            return;
        }
        if (password.length() < 12) {
            log.warn("ADMIN_ZERO_PASSWORD is shorter than 12 characters; super administrator account not created.");
            return;
        }
        UserEntity admin = new UserEntity();
        admin.setPublicUserId(PUBLIC_ID);
        admin.setUsername(USERNAME);
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(RoleEntity.ADMIN_ZERO);
        userRepository.save(admin);
        log.info("Super administrator account {} created.", USERNAME);
    }
}
