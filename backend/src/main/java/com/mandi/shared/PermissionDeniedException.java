package com.mandi.shared;

/** The signed-in member is not allowed to do this in the active firm (for example an employee changing firm settings). */
public class PermissionDeniedException extends RuntimeException {
    public PermissionDeniedException() {
        super("error.permissionDenied");
    }
}
