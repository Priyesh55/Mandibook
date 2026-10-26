package com.mandi.shared;

public class BusinessRuleException extends RuntimeException {
    private final transient Object[] args;

    public BusinessRuleException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args;
    }

    public Object[] args() {
        return args;
    }
}
