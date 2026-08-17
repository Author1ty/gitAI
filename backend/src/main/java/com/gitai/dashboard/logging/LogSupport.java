package com.gitai.dashboard.logging;

import java.util.List;
import java.util.regex.Pattern;

/** Small logging helpers that keep credentials and tokens out of application logs. */
public final class LogSupport {
    private static final Pattern USERINFO = Pattern.compile("(?i)(https?://|git://|ssh://)([^/@\\s]+)@");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)([?&](?:token|password|passwd|pwd|secret|access_token|auth|private[_-]?key)=)[^&\\s]+");
    private static final Pattern SCP_USERINFO = Pattern.compile("(?i)(^|\\s)([^/@\\s:]+):([^/@\\s]+)@(?=[^\\s]+)");

    private LogSupport() { }

    public static String safeRemote(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String sanitized = USERINFO.matcher(value).replaceAll("$1***@");
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1***");
        return SCP_USERINFO.matcher(sanitized).replaceAll("$1***@" );
    }

    public static String safeCommand(List<String> command) {
        return String.join(" ", command.stream().map(LogSupport::safeArgument).toList());
    }

    private static String safeArgument(String value) {
        if (value == null) return "<null>";
        if (value.contains("://") || value.contains("@") || value.contains("?token=") || value.contains("&token=")) {
            return safeRemote(value);
        }
        return value;
    }

    public static String safeExceptionMessage(Throwable throwable) {
        if (throwable == null) return "<unknown>";
        return safeRemote(throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage());
    }
}
