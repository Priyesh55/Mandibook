package com.mandi.security;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Business-user sign-in and sign-out. Firm selection, MFA, recovery and password change endpoints are later slices.
 * Error codes are stable and generic: a wrong password, an unknown email, a disabled or locked account are all
 * {@code INVALID_CREDENTIALS}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** The authority of every signed-in business user. Platform authorities live in another namespace (PLATFORM_*) and another application. */
    static final GrantedAuthority BUSINESS_USER = new SimpleGrantedAuthority("BUSINESS_USER");

    public record LoginRequest(String email, String password) {
        @Override
        public String toString() {
            return "LoginRequest[password=<hidden>]";
        }
    }

    private final AuthService auth;
    private final AuthEventService events;
    private final SecurityProps props;
    private final Clock clock;
    private final CsrfTokenRepository csrfRepository;
    private final SecurityContextRepository contextRepository;
    private final String csrfCookieName;

    public AuthController(AuthService auth, AuthEventService events, SecurityProps props, Clock clock, CsrfTokenRepository csrfRepository,
                          SecurityContextRepository contextRepository,
                          @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies) {
        this.auth = auth;
        this.events = events;
        this.props = props;
        this.clock = clock;
        this.csrfRepository = csrfRepository;
        this.contextRepository = contextRepository;
        this.csrfCookieName = secureCookies ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN";
    }

    /** Public. Issues the CSRF cookie; the page echoes its value in the header on every state-changing request. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "cookieName", csrfCookieName, "token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        if (body == null || body.email() == null || body.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
        }
        AuthService.LoginResult result = auth.login(body.email(), body.password(), req.getRemoteAddr());
        switch (result.outcome()) {
            case THROTTLED:
                return ResponseEntity.status(429).header("Retry-After", String.valueOf(result.retryAfterSeconds()))
                        .body(Map.of("error", "TOO_MANY_ATTEMPTS"));
            case BUSY:
                return ResponseEntity.status(503).header("Retry-After", "1").body(Map.of("error", "SERVER_BUSY"));
            case INVALID:
                return ResponseEntity.status(401).body(Map.of("error", "INVALID_CREDENTIALS"));
            default:
                break;
        }
        AuthenticatedUser user = result.user();
        // A new session for every sign-in: an existing one (a previous user, a planted id) is destroyed, so no session id
        // and no attribute survives authentication.
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of(BUSINESS_USER));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // A NEW CSRF token for the new session. Written explicitly: Spring's deferred token would only create the cookie when
        // something reads it, and the old cookie must not stay valid after sign-in.
        csrfRepository.saveToken(csrfRepository.generateToken(req), req, res);
        contextRepository.saveContext(context, req, res);                    // creates the new session
        HttpSession session = req.getSession();
        session.setMaxInactiveInterval((int) props.session().idleTimeout().toSeconds());
        session.setAttribute(SessionPolicyFilter.AUTH_AT, clock.instant().getEpochSecond());
        return ResponseEntity.ok(sessionBody(user, session));
    }

    /** Public (idempotent) but CSRF-protected. Ends the server session and clears both cookies. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        csrfRepository.saveToken(null, req, res);
        if (a != null && a.getPrincipal() instanceof AuthenticatedUser u) {
            events.record(AuthEventService.Type.LOGOUT, u.id(), null, null, null);
        }
        return ResponseEntity.noContent().build();
    }

    /** Who am I and when does the session end. An ordinary request: it counts as activity. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication, HttpServletRequest req) {
        return current(authentication, req);
    }

    /** Sent by the page after real keyboard or pointer input only. Counts as activity (that is its purpose). */
    @PostMapping("/keepalive")
    public ResponseEntity<Map<String, Object>> keepAlive(Authentication authentication, HttpServletRequest req) {
        return current(authentication, req);
    }

    private ResponseEntity<Map<String, Object>> current(Authentication authentication, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        }
        return ResponseEntity.ok(sessionBody(user, session));
    }

    private Map<String, Object> sessionBody(AuthenticatedUser user, HttpSession session) {
        Long authAt = (Long) session.getAttribute(SessionPolicyFilter.AUTH_AT);
        Map<String, Object> userBody = new LinkedHashMap<>();
        userBody.put("id", user.id());
        userBody.put("email", user.email());
        userBody.put("displayName", user.displayName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", userBody);
        Object activeFirm = session.getAttribute(FirmContextFilter.ACTIVE_FIRM);
        if (activeFirm != null) out.put("activeFirmId", activeFirm);
        out.put("idleExpiresAt", Instant.ofEpochMilli(session.getLastAccessedTime() + session.getMaxInactiveInterval() * 1000L).toString());
        out.put("absoluteExpiresAt", Instant.ofEpochSecond(authAt + props.session().absoluteLifetime().toSeconds()).toString());
        return out;
    }
}
