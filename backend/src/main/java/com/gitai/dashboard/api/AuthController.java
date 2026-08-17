package com.gitai.dashboard.api;

import com.gitai.dashboard.auth.AuditService;
import com.gitai.dashboard.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AuditService audit;

    public AuthController(AuthService auth, AuditService audit) { this.auth = auth; this.audit = audit; }

    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request.username(), request.password());
        audit.record(result.user(), "LOGIN", "session", null, "Placeholder external authentication completed");
        return result;
    }

    @GetMapping("/me")
    public AuthUserView me() { return AuthUserView.from(auth.currentUser()); }

    @PostMapping("/logout")
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var user = auth.currentUser();
        if (authorization != null && authorization.startsWith("Bearer ")) auth.logout(authorization.substring(7).trim());
        audit.record(user, "LOGOUT", "session", null, "Session invalidated");
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record AuthUserView(long id, String username, String displayName, String role, Long departmentId) {
        static AuthUserView from(com.gitai.dashboard.auth.CurrentUser user) {
            return new AuthUserView(user.id(), user.username(), user.displayName(), user.role().name(), user.departmentId());
        }
    }
}
