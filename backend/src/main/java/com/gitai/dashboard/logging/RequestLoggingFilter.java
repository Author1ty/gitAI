package com.gitai.dashboard.logging;

import com.gitai.dashboard.auth.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Logs every HTTP request without logging request bodies, passwords, tokens or authorization headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || !requestId.matches("[A-Za-z0-9._-]+")) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        boolean quiet = isQuietRequest(request);
        if (quiet) {
            log.debug("http request started method={} path={}", request.getMethod(), request.getRequestURI());
        } else {
            log.info("http request started method={} path={} remote={}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        }
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException throwable) {
            long durationMs = elapsedMs(started);
            log.error("http request failed method={} path={} status={} durationMs={} errorType={} error={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                    throwable.getClass().getSimpleName(), LogSupport.safeExceptionMessage(throwable), throwable);
            throw throwable;
        } finally {
            long durationMs = elapsedMs(started);
            String user = currentUser(request);
            if (quiet) {
                log.debug("http request completed method={} path={} status={} durationMs={} user={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, user);
            } else if (response.getStatus() >= 500) {
                log.error("http request completed method={} path={} status={} durationMs={} user={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, user);
            } else if (response.getStatus() >= 400) {
                log.warn("http request completed method={} path={} status={} durationMs={} user={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, user);
            } else {
                log.info("http request completed method={} path={} status={} durationMs={} user={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, user);
            }
            MDC.remove(REQUEST_ID);
        }
    }

    private boolean isQuietRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/health".equals(path) || path.startsWith("/assets/") || "/favicon.ico".equals(path)
                || "/robots.txt".equals(path) || "/manifest.webmanifest".equals(path);
    }

    private String currentUser(HttpServletRequest request) {
        Object requestUser = request.getAttribute("gitAi.currentUser");
        if (requestUser instanceof CurrentUser user) return user.username();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) return user.username();
        return "anonymous";
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}

