package com.mandi.security;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/v1/auth/session}: how long the session has left, WITHOUT counting as activity.
 *
 * <p>This must be a servlet filter that runs BEFORE the session filter and answers the request itself. The decision memo
 * (10.3) measured that any ordinary controller on this path, even one that only reads the session store, extends the idle
 * timer on every call, which would let an open tab keep a session alive for ever. This filter reads the session row with
 * one read-only query and never loads or touches the session. It reports remaining time only, no identity, and creates
 * nothing. The absolute limit is measured from the session's creation, which is the sign-in (sessions are created
 * at login only, never for anonymous requests).
 */
public class PassiveSessionStatusFilter extends OncePerRequestFilter {

    public static final String PATH = "/api/v1/auth/session";

    private final JdbcTemplate jdbc;
    private final SecurityProps props;
    private final String cookieName;

    public PassiveSessionStatusFilter(DataSource dataSource, SecurityProps props, String cookieName) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props;
        this.cookieName = cookieName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !(PATH.equals(req.getRequestURI()) && "GET".equals(req.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException {
        res.setContentType("application/json");
        res.setHeader("Cache-Control", "no-store");
        String id = sessionIdFromCookie(req);
        if (id == null) {
            res.getWriter().write("{\"status\":\"none\"}");
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select last_access_time, max_inactive_interval, creation_time from spring_session where session_id = ?", id);
        if (rows.isEmpty()) {
            res.getWriter().write("{\"status\":\"expired\"}");
            return;
        }
        long now = System.currentTimeMillis();      // the clock Spring Session itself uses for last access
        long last = ((Number) rows.get(0).get("last_access_time")).longValue();
        long maxIdle = ((Number) rows.get(0).get("max_inactive_interval")).longValue();
        long created = ((Number) rows.get(0).get("creation_time")).longValue();
        long idleLeft = maxIdle - (now - last) / 1000;
        long absoluteLeft = props.session().absoluteLifetime().toSeconds() - (now - created) / 1000;
        if (idleLeft <= 0 || absoluteLeft <= 0) {
            res.getWriter().write("{\"status\":\"expired\"}");
            return;
        }
        res.getWriter().write("{\"status\":\"ok\",\"idleSecondsLeft\":" + idleLeft + ",\"absoluteSecondsLeft\":" + absoluteLeft + "}");
    }

    private String sessionIdFromCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (c.getName().equals(cookieName)) {
                try {
                    String id = new String(Base64.getDecoder().decode(c.getValue()), java.nio.charset.StandardCharsets.UTF_8);
                    return id.length() == 36 ? id : null;
                } catch (IllegalArgumentException notBase64) {
                    return null;
                }
            }
        }
        return null;
    }
}
