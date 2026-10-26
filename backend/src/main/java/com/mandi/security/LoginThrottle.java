package com.mandi.security;

import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixed-window attempt counters in the database, so every application instance sees the same numbers. One atomic upsert
 * counts an attempt and tells the caller the total, in its own transaction (the count survives a rolled-back request).
 * Keys are HMACs ({@link SecurityHashes}); no email or address is stored.
 */
@Service
public class LoginThrottle {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public LoginThrottle(DataSource dataSource, PlatformTransactionManager tm) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.ownTransaction = new TransactionTemplate(tm);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Counts one attempt and returns the attempts in the current window including this one. */
    public int hit(byte[] keyHmac, Duration window) {
        double seconds = window.toMillis() / 1000.0;
        Integer attempts = ownTransaction.execute(s -> jdbc.queryForObject("""
                insert into auth_throttle (key_hmac, window_start, attempts) values (?, now(), 1)
                on conflict (key_hmac) do update set
                    attempts = case when auth_throttle.window_start <= now() - make_interval(secs => ?) then 1 else auth_throttle.attempts + 1 end,
                    window_start = case when auth_throttle.window_start <= now() - make_interval(secs => ?) then now() else auth_throttle.window_start end
                returning attempts""", Integer.class, keyHmac, seconds, seconds));
        return attempts == null ? Integer.MAX_VALUE : attempts;
    }

    /** A successful sign-in clears that account's counter (never the address counter). */
    public void reset(byte[] keyHmac) {
        ownTransaction.executeWithoutResult(s -> jdbc.update("delete from auth_throttle where key_hmac = ?", keyHmac));
    }

    /** Seconds until the window of this key ends (at least 1), for the Retry-After header. */
    public long secondsUntilWindowEnds(byte[] keyHmac, Duration window) {
        Double left = jdbc.query("select extract(epoch from (window_start + make_interval(secs => ?) - now())) from auth_throttle where key_hmac = ?",
                rs -> rs.next() ? rs.getDouble(1) : null, window.toMillis() / 1000.0, keyHmac);
        return left == null ? 1 : Math.max(1, (long) Math.ceil(left));
    }

    /** Housekeeping: windows that ended long ago carry no information. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void purgeOldWindows() {
        ownTransaction.executeWithoutResult(s -> jdbc.update("delete from auth_throttle where window_start < now() - interval '1 day'"));
    }
}
