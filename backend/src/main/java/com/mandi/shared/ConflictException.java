package com.mandi.shared;

/** The request is valid but conflicts with the current state (HTTP 409); the message is a message key. */
public class ConflictException extends RuntimeException {
    public ConflictException(String messageKey) {
        super(messageKey);
    }
}
