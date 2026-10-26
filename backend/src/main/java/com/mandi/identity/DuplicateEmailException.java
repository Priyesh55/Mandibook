package com.mandi.identity;

/** An account with this (normalized) email already exists. Callers on public endpoints must not reveal it to strangers. */
public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("an account with this email already exists");
    }
}
