package com.mandi.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Second line of defence against cross-site requests (the CSRF token is the first): a state-changing request that carries
 * an {@code Origin} header must name one of the configured origins (the public address of the web application, which is
 * what a browser sends through the proxy). {@code Origin: null} and any other site are refused. A request with no Origin
 * (a non-browser client) is not refused here; it still needs a valid CSRF token and a session.
 */
public class OriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final List<String> allowed;

    public OriginFilter(List<String> allowedOrigins) {
        this.allowed = allowedOrigins.stream().map(o -> o.strip().toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String origin = req.getHeader("Origin");
        if (origin != null && !SAFE.contains(req.getMethod()) && !allowed.contains(origin.strip().toLowerCase(Locale.ROOT))) {
            JsonErrors.write(res, 403, "ORIGIN_NOT_ALLOWED");
            return;
        }
        chain.doFilter(req, res);
    }
}
