package com.mandi.onboarding;

/** A refusal with a stable code; the controller advice turns it into {@code {"error":CODE}} with the status. */
public class OnboardingException extends RuntimeException {

    private final int status;
    private final String code;
    private final String reason;

    public OnboardingException(int status, String code) {
        this(status, code, null);
    }

    public OnboardingException(int status, String code, String reason) {
        super(code);
        this.status = status;
        this.code = code;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
