package com.gitai.dashboard.git;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes GitAiSyncService against real local Git remotes. The file:// remote exercises the same
 * clone/fetch/mirror code path used by HTTPS/SSH remotes without requiring network credentials.
 */
class GitAiSyncServiceRemoteRepositoryTests {
    Path tempDir;

    private JdbcTemplate jdbc;
    private GitAiSyncService service;
    private Path remote;
    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("git-ai-remote-test-");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:git_remote_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);

        GitAiProperties properties = new GitAiProperties();
        properties.setMirrorRoot(tempDir.resolve("mirrors").toString());
        properties.setBinDirectory("");
        properties.setCommandTimeout(Duration.ofSeconds(20));
        properties.setCloneTimeout(Duration.ofSeconds(20));
        properties.setMaxCommitsPerSync(2);
        service = new GitAiSyncService(jdbc, new GitCommandService(properties), properties, new GitAiNoteParser());

        remote = tempDir.resolve("remote.git");
        work = tempDir.resolve("work");
        runGit(tempDir, "init", "--bare", "--initial-branch=main", remote.toString());
        runGit(tempDir, "init", "--initial-branch=main", work.toString());
        runGit(work, "config", "user.name", "Remote Test Author");
        runGit(work, "config", "user.email", "remote-test@example.test");
        runGit(work, "config", "gc.auto", "0");
        runGit(work, "config", "maintenance.auto", "false");
        runGit(work, "remote", "add", "origin", remote.toUri().toString());
        insertRepository(9001L, remote.toUri().toString(), "main");
    }

    @Test
    void clonesMirrorPagesHistoryFetchesNewRemoteCommitsAndUsesUnknownFallback() throws Exception {
        commit("one", "one.txt", "one\n");
        commit("two", "two.txt", "two\n");
        commit("three", "three.txt", "three\n");
        runGit(work, "push", "origin", "main");

        GitAiSyncService.SyncResult first = service.syncRepository(9001L);
        assertEquals("SUCCESS", first.status());
        assertEquals(2, first.commits());
        assertFalse(first.historyComplete());
        assertEquals(2, first.historyOffset());
        Path mirror = tempDir.resolve("mirrors/9001-remote-test.git");
        assertTrue(Files.isDirectory(mirror));
        assertTrue(Files.exists(mirror.resolve("HEAD")));
        assertEquals(2, count("select count(*) from commit_attribution_stats where repository_id = 9001"));
        assertEquals(2, sum("select coalesce(sum(unknown_lines), 0) from commit_attribution_stats where repository_id = 9001"));
        assertEquals(2, sum("select coalesce(sum(additions), 0) from commit_attribution_stats where repository_id = 9001"));
        assertEquals(0, sum("select coalesce(sum(deletions), 0) from commit_attribution_stats where repository_id = 9001"));

        GitAiSyncService.SyncResult second = service.syncRepository(9001L);
        assertEquals("SUCCESS", second.status());
        assertEquals(1, second.commits());
        assertTrue(second.historyComplete());
        assertEquals(3, count("select count(*) from commit_attribution_stats where repository_id = 9001"));
        assertEquals(1, count("select count(*) from daily_attribution_stats where repository_id = 9001"));

        commit("four", "four.txt", "four\n");
        runGit(work, "push", "origin", "main");
        GitAiSyncService.SyncResult third = service.syncRepository(9001L);
        assertEquals("SUCCESS", third.status());
        assertEquals(1, third.commits());
        assertTrue(third.historyComplete());
        assertEquals(4, count("select count(*) from commit_attribution_stats where repository_id = 9001"));
        assertEquals(4, sum("select coalesce(sum(unknown_lines), 0) from commit_attribution_stats where repository_id = 9001"));
        assertEquals(1, count("select count(*) from daily_attribution_stats where repository_id = 9001"));
        assertEquals("SUCCESS", jdbc.queryForObject("select last_sync_status from repositories where id = 9001", String.class));
        assertNotNull(jdbc.queryForObject("select synced_head_sha from repositories where id = 9001", String.class));
    }

    @Test
    void failedCloneIsReportedAndPartialMirrorIsRemoved() {
        String missingRemote = tempDir.resolve("does-not-exist.git").toUri().toString();
        jdbc.update("update repositories set git_url = ? where id = 9001", missingRemote);

        GitAiSyncService.SyncResult result = service.syncRepository(9001L);

        assertEquals("FAILED", result.status());
        assertTrue(result.error() != null && !result.error().isBlank());
        assertEquals("FAILED", jdbc.queryForObject("select last_sync_status from repositories where id = 9001", String.class));
        assertTrue(Files.notExists(tempDir.resolve("mirrors/9001-remote-test.git")));
        try (var children = Files.list(tempDir.resolve("mirrors"))) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().contains(".partial-")));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private void insertRepository(long id, String gitUrl, String branch) {
        jdbc.update("""
                insert into repositories (id, project_id, group_id, name, git_url, default_branch,
                last_sync_status, history_offset, history_complete)
                values (?, 1, 1, 'remote-test', ?, ?, 'NOT_SYNCED', 0, false)
                """, id, gitUrl, branch);
    }

    private void commit(String message, String file, String contents) throws Exception {
        Files.writeString(work.resolve(file), contents, StandardCharsets.UTF_8);
        runGit(work, "add", file);
        runGit(work, "commit", "-m", message);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long sum(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static String runGit(Path directory, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        if (directory != null) {
            command.add("-C");
            command.add(directory.toAbsolutePath().toString());
        }
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        process.destroy();
        process.waitFor();
        if (exit != 0) throw new IllegalStateException("Git command failed: " + String.join(" ", command) + "\n" + output);
        return output;
    }
}
