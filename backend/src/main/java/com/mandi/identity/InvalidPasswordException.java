package com.mandi.identity;

/** A password that must not be set. Carries a stable reason (for a localized message later), never the password. */
public class InvalidPasswordException extends RuntimeException {

    public enum Reason {
        EMPTY("password.empty"),
        TOO_SHORT("password.tooShort"),
        /** More than 72 UTF-8 bytes: BCrypt would silently ignore the rest. About 24 Devanagari characters. */
        TOO_LONG_BYTES("password.tooLongBytes"),
        CONTAINS_NUL("password.containsNul");

        private final String messageKey;

        Reason(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final Reason reason;

    public InvalidPasswordException(Reason reason) {
        super(reason.messageKey());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
