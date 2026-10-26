package com.mandi.shared;

/** The same Idempotency-Key was used for a request with different content: refused, never silently replayed. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("error.idempotencyKeyReused");
    }
}
