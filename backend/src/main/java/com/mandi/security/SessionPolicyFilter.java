package com.mandi.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Enforces the ABSOLUTE session lifetime on the server (blueprint 6.4). The moment of sign-in is stored in the session
 * ({@link #AUTH_AT}); once {@code absoluteLifetime} (18 hours) has passed, the session is ended whatever the user did in
 * between: activity never extends it. A signed-in session with no recorded sign-in moment is refused too (fail closed).
 * The idle timeout is the session store's own (maxInactiveInterval, sliding).
 *
 * <p>Every normal response also carries the two expiry moments, so the page can warn the user without polling
 * (a polling endpoint would keep the session alive, see decision memo 10.3).
 */
public class SessionPolicyFilter extends OncePerRequestFilter {

    public static final String AUTH_AT = "mandi.authAt";

    private final Clock clock;
    private final SecurityProps props;
    private final AuthEventService events;

    public SessionPolicyFilter(Clock clock, SecurityProps props, AuthEventService events) {
        this.clock = clock;
        this.props = props;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        HttpSession session = signedIn ? req.getSession(false) : null;
        if (session != null) {
            long now = clock.instant().getEpochSecond();
            long absolute = props.session().absoluteLifetime().toSeconds();
            Object at = session.getAttribute(AUTH_AT);
            if (!(at instanceof Long authAt) || now - authAt >= absolute) {
                Long userId = auth.getPrincipal() instanceof AuthenticatedUser u ? u.id() : null;
                events.record(AuthEventService.Type.SESSION_ABSOLUTE_EXPIRED, userId, null, null, null);
                session.invalidate();
                SecurityContextHolder.clearContext();
                JsonErrors.write(res, 401, "SESSION_EXPIRED");
                return;
            }
            res.setHeader("X-Session-Idle-Expires-At",
                    Instant.ofEpochMilli(session.getLastAccessedTime() + session.getMaxInactiveInterval() * 1000L).toString());
            res.setHeader("X-Session-Absolute-Expires-At", Instant.ofEpochSecond(authAt + absolute).toString());
        }
        chain.doFilter(req, res);
    }
}
