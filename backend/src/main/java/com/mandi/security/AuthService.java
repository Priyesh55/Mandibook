package com.mandi.security;

import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mandi.identity.AppUser;
import com.mandi.identity.AppUserRepository;
import com.mandi.identity.EmailNormalizer;
import com.mandi.identity.PasswordService;
import com.mandi.identity.UserStatus;

/**
 * Checks an email and password against the persisted business identities (table app_user ONLY: a platform administrator
 * is in another schema and can never sign in here).
 *
 * <p>The outcome for a wrong password, an unknown email, a disabled or temporarily locked account is the same
 * {@link Outcome#INVALID}, after the same amount of work: an unknown email is checked against a dummy hash of the same cost.
 * Attempts are counted BEFORE any hashing (per email and per address) and the hashing itself is bounded
 * ({@link HashLimiter}). Every outcome writes a security event in its own transaction.
 */
@Service
public class AuthService {

    public enum Outcome { SUCCESS, INVALID, THROTTLED, BUSY }

    public record LoginResult(Outcome outcome, AuthenticatedUser user, long retryAfterSeconds) {
        static LoginResult of(Outcome o) {
            return new LoginResult(o, null, 0);
        }
    }

    private final AppUserRepository users;
    private final PasswordService passwords;
    private final SecurityProps props;
    private final SecurityHashes hashes;
    private final LoginThrottle throttle;
    private final HashLimiter limiter;
    private final AuthEventService events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordService passwords, SecurityProps props, SecurityHashes hashes,
                       LoginThrottle throttle, HashLimiter limiter, AuthEventService events, DataSource dataSource,
                       PlatformTransactionManager tm) {
        this.users = users;
        this.passwords = passwords;
        this.props = props;
        this.hashes = hashes;
        this.throttle = throttle;
        this.limiter = limiter;
        this.events = events;
        this.jdbc = new JdbcTemplate(dataSource);
        this.ownTransaction = new TransactionTemplate(tm);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // computed once at start-up (not on the first unknown email), so every failed attempt costs the same
        this.dummyHash = passwords.hash("not-a-real-password-" + java.util.UUID.randomUUID());
    }

    public LoginResult login(String rawEmail, String rawPassword, String clientAddress) {
        byte[] ipKey = hashes.hmac("ip", clientAddress == null ? "unknown" : clientAddress);
        String email;
        try {
            email = EmailNormalizer.normalize(rawEmail);
        } catch (EmailNormalizer.InvalidEmailException notAnEmail) {
            // counts against the address only; nothing is stored about what was typed
            int ipAttempts = throttle.hit(ipKey, props.login().window());
            if (ipAttempts > props.login().maxAttemptsPerIp()) {
                events.record(AuthEventService.Type.LOGIN_THROTTLED, null, null, ipKey, "address");
                return new LoginResult(Outcome.THROTTLED, null, throttle.secondsUntilWindowEnds(ipKey, props.login().window()));
            }
            events.record(AuthEventService.Type.LOGIN_FAILURE, null, null, ipKey, "malformed email");
            return LoginResult.of(Outcome.INVALID);
        }
        byte[] emailKey = hashes.hmac("email", email);

        // 1. count the attempt first, so that throttled requests cost no hashing at all
        int ipAttempts = throttle.hit(ipKey, props.login().window());
        int emailAttempts = throttle.hit(emailKey, props.login().window());
        boolean ipExceeded = ipAttempts > props.login().maxAttemptsPerIp();
        boolean emailExceeded = emailAttempts > props.login().maxAttemptsPerEmail();
        if (ipExceeded || emailExceeded) {
            byte[] key = emailExceeded ? emailKey : ipKey;
            events.record(AuthEventService.Type.LOGIN_THROTTLED, null, emailKey, ipKey, emailExceeded ? "email" : "address");
            return new LoginResult(Outcome.THROTTLED, null, throttle.secondsUntilWindowEnds(key, props.login().window()));
        }

        // 2. bounded verification work
        if (!limiter.tryAcquire()) {
            return LoginResult.of(Outcome.BUSY);
        }
        try {
            Optional<AppUser> found = users.findByEmail(email);
            String hash = found.map(AppUser::getPasswordHash).orElse(dummyHash);
            boolean passwordOk = passwords.matches(rawPassword, hash);
            AppUser user = found.orElse(null);
            if (user == null || !passwordOk) {
                if (user != null) recordFailure(user, emailKey, ipKey);
                else events.record(AuthEventService.Type.LOGIN_FAILURE, null, emailKey, ipKey, "bad credentials");
                return LoginResult.of(Outcome.INVALID);
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                events.record(AuthEventService.Type.LOGIN_FAILURE, user.getId(), emailKey, ipKey, "account disabled");
                return LoginResult.of(Outcome.INVALID);
            }
            Instant lockedUntil = user.getLockedUntil();
            if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
                events.record(AuthEventService.Type.LOGIN_LOCKED, user.getId(), emailKey, ipKey, "account locked");
                return LoginResult.of(Outcome.INVALID);
            }
            ownTransaction.executeWithoutResult(s -> jdbc.update(
                    "update app_user set failed_login_count = 0, locked_until = null, last_login_at = now() where id = ?", user.getId()));
            throttle.reset(emailKey);
            events.record(AuthEventService.Type.LOGIN_SUCCESS, user.getId(), emailKey, ipKey, null);
            return new LoginResult(Outcome.SUCCESS, new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName()), 0);
        } finally {
            limiter.release();
        }
    }

    /** Counts a wrong password and locks the account after too many in a row. Atomic, so parallel guesses cannot dodge it. */
    private void recordFailure(AppUser user, byte[] emailKey, byte[] ipKey) {
        Boolean locked = ownTransaction.execute(s -> jdbc.queryForObject("""
                update app_user set
                    failed_login_count = failed_login_count + 1,
                    locked_until = case when failed_login_count + 1 >= ? then now() + make_interval(secs => ?) else locked_until end
                where id = ?
                returning (failed_login_count >= ?)""", Boolean.class,
                props.login().maxFailuresBeforeLock(), props.login().lockDuration().toMillis() / 1000.0, user.getId(),
                props.login().maxFailuresBeforeLock()));
        events.record(Boolean.TRUE.equals(locked) ? AuthEventService.Type.LOGIN_LOCKED : AuthEventService.Type.LOGIN_FAILURE,
                user.getId(), emailKey, ipKey, Boolean.TRUE.equals(locked) ? "locked after repeated failures" : "bad credentials");
    }
}
