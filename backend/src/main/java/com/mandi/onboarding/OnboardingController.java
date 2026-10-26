package com.mandi.onboarding;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mandi.security.AuthenticatedUser;
import com.mandi.security.LoginThrottle;
import com.mandi.security.SecurityHashes;
import com.mandi.security.SecurityProps;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Entry into the system: accepting an invitation and creating a firm. Everything here is under {@code /api/v1/auth/}, so
 * no firm is selected yet. Only {@code invitations/accept} is reachable without a session (it needs the emailed token, the
 * CSRF token and passes the same throttle as sign-in); the others need a signed-in person.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class OnboardingController {

    public record AcceptRequest(String token, String password, String displayName) {
        @Override
        public String toString() {
            return "AcceptRequest[token=<hidden>, password=<hidden>]";
        }
    }

    public record TokenRequest(String token) {
        @Override
        public String toString() {
            return "TokenRequest[token=<hidden>]";
        }
    }

    public record FirmRequest(String name, String nameHi) {
    }

    private final OnboardingService onboarding;
    private final LoginThrottle throttle;
    private final SecurityHashes hashes;
    private final SecurityProps props;

    public OnboardingController(OnboardingService onboarding, LoginThrottle throttle, SecurityHashes hashes, SecurityProps props) {
        this.onboarding = onboarding;
        this.throttle = throttle;
        this.hashes = hashes;
        this.props = props;
    }

    /** Public. An invited person without an account chooses a password; sign-in is a separate, normal step afterwards. */
    @PostMapping("/invitations/accept")
    public ResponseEntity<Map<String, String>> accept(@RequestBody(required = false) AcceptRequest body, HttpServletRequest req) {
        // the same per-address window as sign-in: guessing tokens is as limited as guessing passwords
        int attempts = throttle.hit(hashes.hmac("invite-ip", req.getRemoteAddr() == null ? "unknown" : req.getRemoteAddr()), props.login().window());
        if (attempts > props.login().maxAttemptsPerIp()) throw new OnboardingException(429, "TOO_MANY_ATTEMPTS");
        if (body == null || body.token() == null || body.password() == null) throw new OnboardingException(400, "INVALID_REQUEST");
        String kind = onboarding.accept(body.token(), body.displayName(), body.password());
        return ResponseEntity.ok(Map.of("status", "ACCEPTED", "kind", kind));
    }

    /** Signed in. An existing account accepts an employee invitation made for its own email address. */
    @PostMapping("/invitations/accept-signed-in")
    public ResponseEntity<Map<String, String>> acceptSignedIn(@RequestBody(required = false) TokenRequest body, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) throw new OnboardingException(401, "UNAUTHENTICATED");
        if (body == null || body.token() == null) throw new OnboardingException(400, "INVALID_REQUEST");
        onboarding.acceptSignedIn(user, body.token());
        return ResponseEntity.ok(Map.of("status", "ACCEPTED", "kind", "EMPLOYEE"));
    }

    /** Signed in, owner account required: creates a firm and makes the caller its OWNER. */
    @PostMapping("/firms")
    public ResponseEntity<OnboardingService.NewFirm> createFirm(@RequestBody(required = false) FirmRequest body, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) throw new OnboardingException(401, "UNAUTHENTICATED");
        if (body == null) throw new OnboardingException(400, "INVALID_REQUEST");
        return ResponseEntity.status(201).body(onboarding.createFirm(user, body.name(), body.nameHi()));
    }
}
