package com.mandi.onboarding;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The default: no email delivery. Creating an invitation fails (503 EMAIL_NOT_CONFIGURED) and stores nothing. */
@Component
@ConditionalOnProperty(name = "mandi.mail.dev-sink", havingValue = "false", matchIfMissing = true)
class UnconfiguredMailer implements InvitationMailer {

    @Override
    public void sendInvitation(String toEmail, String kind, String organisation, String link, Instant expiresAt) {
        throw new MailNotConfiguredException();
    }
}
