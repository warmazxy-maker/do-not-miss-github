package com.donotmiss.backend.auth;

import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.ratelimit.ClientIp;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.donotmiss.backend.ratelimit.RateLimited;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @RateLimited(RateLimitTier.REGISTER)
    public AuthDtos.AuthResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @RateLimited(RateLimitTier.LOGIN)
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request, ClientIp.of(servletRequest));
    }

    @GetMapping("/me")
    public AuthDtos.UserResponse me(HttpServletRequest request) {
        return authService.me(currentUser.id(request));
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        authService.logout(currentUser.token(request));
    }
}
