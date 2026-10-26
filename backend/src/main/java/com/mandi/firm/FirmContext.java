package com.mandi.firm;

import com.mandi.identity.MemberRole;

/**
 * Who is acting in which firm, for ONE request. Built only by the server from the signed-in session after the membership,
 * firm and account checks passed (see {@code com.mandi.security.FirmContextFilter}); nothing in it comes from the request.
 */
public record FirmContext(long firmId, long userId, long membershipId, MemberRole role) {

    /** Request attribute under which the filter publishes the context. */
    public static final String ATTRIBUTE = FirmContext.class.getName();
}
