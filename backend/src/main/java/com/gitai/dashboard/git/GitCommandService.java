package com.gitai.dashboard.git;

import com.gitai.dashboard.logging.LogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@Service
public class GitCommandService {
    private static final Logger log = LoggerFactory.getLogger(GitCommandService.class);

    private final GitAiProperties properties;

    public GitCommandService(GitAiProperties properties) {
        this.properties = properties;
    }

    public String runGit(Path repository, String... arguments) {
        CommandResult result = runGitAllowFailure(repository, arguments);
        if (result.exitCode() != 0) {
            throw failed(commandForRepository(repository, arguments), result);
        }
        return result.output();
    }

    /** Executes a Git command while preserving its exit code for expected probes, such as note existence checks. */
    public CommandResult runGitAllowFailure(Path repository, String... arguments) {
        return run(commandForRepository(repository, arguments), null, properties.getCommandTimeout());
    }

    public String cloneMirror(String remoteUrl, Path mirrorPath) {
        List<String> command = List.of("git", "clone", "--mirror", remoteUrl, mirrorPath.toAbsolutePath().normalize().toString());
        CommandResult result = run(command, null, properties.getCloneTimeout());
        if (result.exitCode() != 0) throw failed(command, result);
        return result.output();
    }

    private List<String> commandForRepository(Path repository, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toAbsolutePath().normalize().toString());
        command.addAll(List.of(arguments));
        return command;
    }

    private CommandResult run(List<String> command, Path workingDirectory, Duration timeout) {
        long started = System.nanoTime();
        String safeCommand = LogSupport.safeCommand(command);
        log.debug("git command started timeout={} command={}", timeout, safeCommand);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workingDirectory != null) processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        addGitAiDirectory(processBuilder.environment());

        try {
            Process process = processBuilder.start();
            FutureTask<String> outputTask = new FutureTask<>(() -> readBounded(process.getInputStream(), properties.getMaxCommandOutputBytes()));
            Thread.ofVirtual().name("git-command-output").start(outputTask);
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (outputTask.isDone()) {
                    try {
                        outputTask.get();
                    } catch (ExecutionException exception) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                        throw outputFailure(command, exception.getCause());
                    }
                }
                if (System.nanoTime() >= deadlineNanos) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new GitCommandException("Git command timed out (" + timeout + "): " + LogSupport.safeCommand(command));
                }
            }
            String output;
            try {
                output = outputTask.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                throw outputFailure(command, exception.getCause());
            } catch (java.util.concurrent.TimeoutException exception) {
                throw new GitCommandException("Git command output did not finish: " + LogSupport.safeCommand(command), exception);
            }
            int exitCode = process.exitValue();
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            log.debug("git command completed exitCode={} durationMs={} outputBytes={} command={}", exitCode,
                    elapsedMs(started), output.getBytes(StandardCharsets.UTF_8).length, safeCommand);
            return new CommandResult(exitCode, output);
        } catch (IOException exception) {
            log.error("git command could not start durationMs={} command={} error={}", elapsedMs(started), safeCommand,
                    LogSupport.safeExceptionMessage(exception), exception);
            throw new GitCommandException("Unable to start Git command: " + LogSupport.safeCommand(command), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("git command interrupted durationMs={} command={}", elapsedMs(started), safeCommand);
            throw new GitCommandException("Git command was interrupted: " + LogSupport.safeCommand(command), exception);
        }
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String readBounded(InputStream input, long maximumBytes) throws IOException {
        try (input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new OutputLimitExceededException("Git command output exceeds the configured " + maximumBytes + " byte limit");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private GitCommandException outputFailure(List<String> command, Throwable cause) {
        return new GitCommandException("Unable to read Git command output: " + LogSupport.safeCommand(command) + ". " + LogSupport.safeExceptionMessage(cause), cause);
    }

    private GitCommandException failed(List<String> command, CommandResult result) {
        return new GitCommandException("Git command failed (exit=" + result.exitCode() + "): " + LogSupport.safeCommand(command)
                + "\n" + LogSupport.safeRemote(abbreviate(result.output())));
    }

    private void addGitAiDirectory(Map<String, String> environment) {
        String directory = properties.getBinDirectory();
        if (directory == null || directory.isBlank()) return;
        String currentPath = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        String value = directory + java.io.File.pathSeparator + currentPath;
        environment.put("PATH", value);
        if (environment.containsKey("Path")) environment.put("Path", value);
    }

    private String abbreviate(String output) {
        if (output == null || output.length() <= 4_000) return output == null ? "" : output;
        return output.substring(0, 4_000) + "\n... (output truncated)";
    }

    public record CommandResult(int exitCode, String output) {}

    private static class OutputLimitExceededException extends IOException {
        OutputLimitExceededException(String message) { super(message); }
    }

    public static class GitCommandException extends RuntimeException {
        public GitCommandException(String message) { super(message); }
        public GitCommandException(String message, Throwable cause) { super(message, cause); }
    }
}

