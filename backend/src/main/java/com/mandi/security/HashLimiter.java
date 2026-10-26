package com.mandi.security;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Bounds the password-verification work. A BCrypt check at cost 12 takes about 0.4 s of CPU, so an unbounded login
 * endpoint is a denial-of-service lever: only a few verifications run at once and the rest wait briefly, then are refused
 * as busy (the caller answers 503). The throttle limits attempts per email and address; this limits the CPU.
 */
@Component
public class HashLimiter {

    private final Semaphore slots;
    private final long waitMillis;

    public HashLimiter(SecurityProps props) {
        this.slots = new Semaphore(props.login().effectiveMaxConcurrentHashes(), true);
        this.waitMillis = props.login().hashWaitTimeout().toMillis();
    }

    /** @return true when a slot was obtained; the caller MUST then call {@link #release()} in a finally block */
    public boolean tryAcquire() {
        try {
            return slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        slots.release();
    }

    /** For tests: how many verifications could start right now. */
    public int availableSlots() {
        return slots.availablePermits();
    }
}
