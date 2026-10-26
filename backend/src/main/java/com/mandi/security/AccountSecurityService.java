package com.mandi.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.identity.AppUser;
import com.mandi.identity.AppUserRepository;
import com.mandi.identity.PasswordService;

/**
 * Account changes that must end a person's sessions at once (blueprint 6.2, invalidation triggers): a new password and
 * disabling the account. There is no endpoint for them yet; the services exist so that the rule is implemented and tested
 * once. Each writes a security event (never the password).
 */
@Service
public class AccountSecurityService {

    private final AppUserRepository users;
    private final PasswordService passwords;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final AuthEventService events;

    public AccountSecurityService(AppUserRepository users, PasswordService passwords,
                                  FindByIndexNameSessionRepository<? extends Session> sessions, AuthEventService events) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.events = events;
    }

    /**
     * Sets a new password (72-byte and length rules apply), then deletes the user's sessions, except {@code keepSessionId}
     * (the session that made the change; pass null after a password RESET, which ends all of them).
     *
     * @return the number of sessions ended
     */
    @Transactional
    public int changePassword(long userId, String newPassword, String keepSessionId) {
        AppUser user = users.findById(userId).orElseThrow();
        user.changePasswordHash(passwords.hash(newPassword));
        users.saveAndFlush(user);
        events.record(AuthEventService.Type.PASSWORD_CHANGED, userId, null, null, null);
        return revokeSessions(userId, keepSessionId);
    }

    @Transactional
    public int disable(long userId) {
        AppUser user = users.findById(userId).orElseThrow();
        user.disable();
        users.saveAndFlush(user);
        events.record(AuthEventService.Type.ACCOUNT_DISABLED, userId, null, null, null);
        return revokeSessions(userId, null);
    }

    @Transactional
    public void enable(long userId) {
        AppUser user = users.findById(userId).orElseThrow();
        user.enable();
        users.saveAndFlush(user);
        events.record(AuthEventService.Type.ACCOUNT_ENABLED, userId, null, null, null);
    }

    /** "Sign out everywhere". */
    @Transactional
    public int revokeSessions(long userId, String keepSessionId) {
        List<String> ids = new ArrayList<>(sessions.findByPrincipalName(AuthenticatedUser.principalName(userId)).keySet());
        int ended = 0;
        for (String id : ids) {
            if (id.equals(keepSessionId)) continue;
            sessions.deleteById(id);
            ended++;
        }
        if (ended > 0) events.record(AuthEventService.Type.SESSIONS_REVOKED, userId, null, null, ended + " sessions");
        return ended;
    }
}
