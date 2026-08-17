package com.gitai.dashboard.auth;

public interface ExternalAuthenticationClient {
    ExternalIdentity authenticate(String username, String password);

    record ExternalIdentity(String username, String displayName) {}
}
