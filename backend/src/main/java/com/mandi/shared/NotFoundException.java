package com.mandi.shared;

public class NotFoundException extends RuntimeException {
    public NotFoundException() {
        super("error.notFound");
    }
}
