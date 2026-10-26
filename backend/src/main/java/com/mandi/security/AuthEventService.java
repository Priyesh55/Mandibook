package com.mandi.security;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the append-only security event log. Each event is written in its OWN transaction, so a failed login (an
 * exception, a rolled-back request) still leaves its record. The log never receives a password, token, secret or session
 * id: only the event type, the user id when known, keyed hashes of the email and address, and a short code.
 */
@Service
public class AuthEventService {

    private static final Logger log = LoggerFactory.getLogger(AuthEventService.class);

    public enum Type {
        LOGIN_SUCCESS, LOGIN_FAILURE, LOGIN_THROTTLED, LOGIN_LOCKED, LOGOUT, SESSION_ABSOLUTE_EXPIRED,
        PASSWORD_CHANGED, ACCOUNT_DISABLED, ACCOUNT_ENABLED, SESSIONS_REVOKED
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public AuthEventService(DataSource dataSource, PlatformTransactionManager tm) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.ownTransaction = new TransactionTemplate(tm);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A failure to write the log is reported (without any detail of the request) and does not deny the user's own action. */
    public void record(Type type, Long userId, byte[] emailHmac, byte[] ipHmac, String details) {
        try {
            ownTransaction.executeWithoutResult(s -> jdbc.update(
                    "insert into auth_event (event_type, user_id, email_hmac, ip_hmac, details) values (?, ?, ?, ?, ?)",
                    type.name(), userId, emailHmac, ipHmac, details));
        } catch (RuntimeException e) {
            log.error("could not record security event {} ({})", type, e.getClass().getSimpleName());
        }
    }
}
