package com.gitai.dashboard.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary adapter only. It deliberately never records the supplied password. Replace this bean's implementation
 * with an HTTP/SSO client when the enterprise identity service is available.
 */
@Component
public class PlaceholderExternalAuthenticationClient implements ExternalAuthenticationClient {
    private final AuthProperties properties;

    public PlaceholderExternalAuthenticationClient(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExternalIdentity authenticate(String username, String password) {
        if (!"placeholder".equalsIgnoreCase(properties.getProvider()) || !properties.isPlaceholderEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The configured external authentication provider is not available");
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        String normalized = username.trim();
        return new ExternalIdentity(normalized, normalized);
    }
}
