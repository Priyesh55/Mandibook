package com.mandi.onboarding;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT AND TEST ONLY. Keeps the last invitation "emails" in memory so a developer (or a browser test) can open the
 * link; it exposes raw tokens by design. {@code mandi.mail.dev-sink=true} is refused at start-up outside the dev and test
 * profiles (StartupSafetyEnvironmentPostProcessor), and the endpoint that shows the messages exists only with it.
 */
@Component
@ConditionalOnProperty(name = "mandi.mail.dev-sink", havingValue = "true")
public class DevMailSink implements InvitationMailer {

    public record Message(Instant at, String to, String kind, String organisation, String link, Instant expiresAt) {
    }

    private static final int KEEP = 50;
    private final Deque<Message> messages = new ArrayDeque<>();

    @Override
    public synchronized void sendInvitation(String toEmail, String kind, String organisation, String link, Instant expiresAt) {
        messages.addFirst(new Message(Instant.now(), toEmail, kind, organisation, link, expiresAt));
        while (messages.size() > KEEP) messages.removeLast();
    }

    public synchronized List<Message> recent() {
        return new ArrayList<>(messages);
    }
}
