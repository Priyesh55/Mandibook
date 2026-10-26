package com.mandi.onboarding;

import java.time.Instant;

/**
 * How an invitation reaches a person. The raw token exists only in the link handed to this port; it is never stored, logged
 * or returned by the API. No production implementation exists yet (the email provider is an open decision, blueprint Q-1):
 * without one, {@link UnconfiguredMailer} refuses to send, so no invitation can be created that nobody can receive.
 */
public interface InvitationMailer {

    /** @throws MailNotConfiguredException when this installation has no way to deliver email */
    void sendInvitation(String toEmail, String kind, String organisation, String link, Instant expiresAt);

    class MailNotConfiguredException extends RuntimeException {
        public MailNotConfiguredException() {
            super("no email delivery is configured");
        }
    }
}
