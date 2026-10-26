package com.mandi.identity;

/** Authentication status of a person (blueprint 10.1). A temporary lock is {@code locked_until}, not a status. */
public enum UserStatus {
    ACTIVE, DISABLED
}
