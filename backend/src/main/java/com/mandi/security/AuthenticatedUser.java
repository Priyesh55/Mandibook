package com.mandi.security;

import java.io.Serializable;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The principal of a signed-in BUSINESS user, as kept in the server-side session. Its name is {@code u:<id>}, never the
 * email, so the session table's principal index holds no personal data. A platform administrator is a different
 * identity in a different schema and can never be this type.
 */
public record AuthenticatedUser(long id, String email, String displayName) implements AuthenticatedPrincipal, Serializable {

    private static final long serialVersionUID = 1L;

    /** The principal name used by the session store index (revoking all sessions of one user). */
    public static String principalName(long userId) {
        return "u:" + userId;
    }

    @Override
    public String getName() {
        return principalName(id);
    }
}
