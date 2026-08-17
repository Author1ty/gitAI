package com.gitai.dashboard.logging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSupportTests {
    @Test
    void redactsCredentialsAndQuerySecrets() {
        String remote = "https://build-user:super-secret@example.com/team/repo.git?token=abc123&x=1";
        String safe = LogSupport.safeRemote(remote);

        assertTrue(safe.contains("https://***@example.com/team/repo.git"));
        assertTrue(safe.contains("token=***"));
        assertFalse(safe.contains("super-secret"));
        assertFalse(safe.contains("abc123"));
    }

    @Test
    void sanitizesCommandArgumentsWithoutHidingRepositoryPaths() {
        String safe = LogSupport.safeCommand(List.of("git", "clone", "https://user:pw@example.com/repo.git", "C:/mirrors/repo.git"));

        assertTrue(safe.contains("https://***@example.com/repo.git"));
        assertTrue(safe.contains("C:/mirrors/repo.git"));
        assertFalse(safe.contains("user:pw"));
    }
}