package com.mandi.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings ({@code mandi.security.*}). The session lifetimes are the approved policy (blueprint 6.4):
 * an ABSOLUTE 18 hours from sign-in that no activity extends, and an idle timeout of 30 minutes (a proposal pending a
 * usability test). The HMAC key and the allowed origins have no default in the production-like baseline.
 */
@ConfigurationProperties("mandi.security")
public record SecurityProps(
        @DefaultValue Session session,
        @DefaultValue Login login,
        @DefaultValue List<String> allowedOrigins,
        String hmacKey) {

    public record Session(
            @DefaultValue("PT18H") Duration absoluteLifetime,
            @DefaultValue("PT30M") Duration idleTimeout) {
    }

    public record Login(
            /** attempts per normalized email in one window (counted before the password is checked) */
            @DefaultValue("10") int maxAttemptsPerEmail,
            /** attempts per client address in one window */
            @DefaultValue("50") int maxAttemptsPerIp,
            @DefaultValue("PT15M") Duration window,
            /** consecutive wrong passwords that lock one account temporarily */
            @DefaultValue("10") int maxFailuresBeforeLock,
            @DefaultValue("PT15M") Duration lockDuration,
            /** password verifications that may run at the same time (each costs about 0.4 s of CPU at cost 12) */
            @DefaultValue("0") int maxConcurrentHashes,
            /** how long a login waits for a free verification slot before it is refused as busy */
            @DefaultValue("PT2S") Duration hashWaitTimeout) {

        public int effectiveMaxConcurrentHashes() {
            return maxConcurrentHashes > 0 ? maxConcurrentHashes : Math.max(2, Runtime.getRuntime().availableProcessors());
        }
    }
}
