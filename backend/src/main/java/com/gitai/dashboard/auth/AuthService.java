package com.gitai.dashboard.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JdbcTemplate jdbc;
    private final ExternalAuthenticationClient externalAuthenticationClient;
    private final AuthProperties properties;
    private final Clock serverClock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, ExternalAuthenticationClient externalAuthenticationClient,
                       AuthProperties properties, Clock serverClock) {
        this.jdbc = jdbc;
        this.externalAuthenticationClient = externalAuthenticationClient;
        this.properties = properties;
        this.serverClock = serverClock;
    }

    public LoginResult login(String username, String password) {
        log.info("login started username={}", username);
        try {
            ExternalAuthenticationClient.ExternalIdentity identity = externalAuthenticationClient.authenticate(username, password);
            CurrentUser user = findEnabledByUsername(identity.username());
            String token = newToken();
            Instant issuedAt = serverClock.instant();
            Instant expiresAt = issuedAt.plus(properties.getSessionTtl());
            long expiresAtEpochMs = expiresAt.toEpochMilli();
            Timestamp issuedAtTimestamp = Timestamp.from(issuedAt);
            jdbc.update("""
                    insert into auth_sessions
                        (user_id, token_hash, expires_at, expires_at_epoch_ms, created_at, last_seen_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, user.id(), sha256(token), Timestamp.from(expiresAt), expiresAtEpochMs,
                    issuedAtTimestamp, issuedAtTimestamp);
            log.info("login succeeded username={} userId={} role={} departmentId={} sessionTtl={} expiresAtEpochMs={}",
                    user.username(), user.id(), user.role(), user.departmentId(), properties.getSessionTtl(), expiresAtEpochMs);
            return new LoginResult(token, expiresAt.toString(), user);
        } catch (RuntimeException exception) {
            log.warn("login failed username={} errorType={} error={}", username, exception.getClass().getSimpleName(),
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            throw exception;
        }
    }

    /**
     * Session expiry is deliberately evaluated with the application server clock, never with the database clock.
     * The persisted epoch value avoids timezone conversion differences between the application JVM and MySQL.
     */
    public CurrentUser authenticateToken(String token) {
        if (token == null || token.isBlank()) return null;
        String tokenHash = sha256(token);
        List<AuthenticatedSession> sessions = jdbc.query("""
                select u.id, u.username, u.display_name, u.role, u.department_id, s.expires_at_epoch_ms
                from auth_sessions s join app_users u on u.id = s.user_id
                where s.token_hash = ? and u.enabled = TRUE
                """, (rs, row) -> new AuthenticatedSession(
                toUser(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("role"), rs.getObject("department_id", Long.class)),
                rs.getObject("expires_at_epoch_ms", Long.class)), tokenHash);
        if (sessions.isEmpty()) return null;

        AuthenticatedSession session = sessions.getFirst();
        long serverNowEpochMs = serverClock.millis();
        if (session.expiresAtEpochMs() == null || session.expiresAtEpochMs() <= serverNowEpochMs) {
            jdbc.update("delete from auth_sessions where token_hash = ?", tokenHash);
            log.info("token rejected reason=expired-or-legacy serverNowEpochMs={} expiresAtEpochMs={}",
                    serverNowEpochMs, session.expiresAtEpochMs());
            return null;
        }

        jdbc.update("update auth_sessions set last_seen_at = ? where token_hash = ?",
                Timestamp.from(serverClock.instant()), tokenHash);
        return session.user();
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            int deleted = jdbc.update("delete from auth_sessions where token_hash = ?", sha256(token));
            log.info("logout completed sessionRemoved={}", deleted > 0);
        }
    }

    public CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) return currentUser;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
    }

    public CurrentUser findEnabledByUsername(String username) {
        List<CurrentUser> users = jdbc.query("""
                select id, username, display_name, role, department_id from app_users
                where username = ? and enabled = TRUE
                """, (rs, row) -> toUser(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getObject("department_id", Long.class)), username);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        return users.getFirst();
    }

    private CurrentUser toUser(long id, String username, String displayName, String role, Long departmentId) {
        try {
            return new CurrentUser(id, username, displayName, AppRole.valueOf(role), departmentId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid application role for " + username, exception);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record AuthenticatedSession(CurrentUser user, Long expiresAtEpochMs) {}

    public record LoginResult(String token, String expiresAt, CurrentUser user) {}
}