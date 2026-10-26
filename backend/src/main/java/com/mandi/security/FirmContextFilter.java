package com.mandi.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mandi.firm.FirmContext;
import com.mandi.identity.MemberRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * THE mechanism that protects every business endpoint (blueprint 8.1 to 8.4): it runs after authentication for every
 * {@code /api/**} request except the sign-in family {@code /api/v1/auth/**}, so a route added tomorrow is covered without any
 * code of its own. Nothing the client sends chooses the firm: the user comes from the server-side session, the active firm
 * from the session too, and a request header is only a cross-check against the active firm.
 *
 * <p>Answers (before the controller runs, nothing executed):
 * <ul>
 *   <li>401 {@code UNAUTHENTICATED}: no or expired session (earlier in the chain), or the account was disabled meanwhile
 *       (the session is ended);</li>
 *   <li>409 {@code FIRM_SELECTION_REQUIRED}: several firms and none selected yet (with exactly one, it is selected
 *       automatically);</li>
 *   <li>409 {@code FIRM_CHECK_REQUIRED} / {@code FIRM_MISMATCH}: the {@code X-Mandi-Firm-Check} header is absent or names
 *       another firm than the session's active one (another tab switched the firm), so a write can never silently land in
 *       a different firm;</li>
 *   <li>403 {@code NO_MEMBERSHIP}: no ACTIVE membership of this user in the active firm (revoked, or never existed);
 *       {@code FIRM_INACTIVE}: the firm is switched off; {@code ACCOUNT_BLOCKED}: the owner's account is suspended or its
 *       trial or paid period has lapsed (and no grace date), or the firm's owner has no account at all (fail closed).</li>
 * </ul>
 * Membership, firm and account state are read from the database on every request (no cache), so a revocation or suspension
 * applies to the very next request.
 */
public class FirmContextFilter extends OncePerRequestFilter {

    public static final String ACTIVE_FIRM = "mandi.activeFirmId";
    public static final String FIRM_CHECK_HEADER = "X-Mandi-Firm-Check";
    private static final java.util.Set<String> ALLOWED_ACCOUNT_STATES = java.util.Set.of("TRIAL", "ACTIVE");

    private final JdbcTemplate jdbc;

    public FirmContextFilter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        // /api/v1/dev/ exists only with the development mail sink (public there, never registered otherwise)
        return !uri.startsWith("/api/") || uri.startsWith("/api/v1/auth/") || uri.startsWith("/api/v1/dev/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = req.getSession(false);
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user) || session == null) {
            JsonErrors.write(res, 401, "UNAUTHENTICATED");
            return;
        }

        Long active = session.getAttribute(ACTIVE_FIRM) instanceof Long l ? l : null;
        if (active == null) {
            List<Long> firms = jdbc.queryForList("select m.firm_id from firm_member m join firm f on f.id = m.firm_id "
                    + "where m.user_id = ? and m.status = 'ACTIVE' and f.active order by m.firm_id limit 2", Long.class, user.id());
            if (firms.size() != 1) {
                JsonErrors.write(res, 409, "FIRM_SELECTION_REQUIRED");
                return;
            }
            active = firms.get(0);
            session.setAttribute(ACTIVE_FIRM, active);
        }

        String check = req.getHeader(FIRM_CHECK_HEADER);
        if (check == null || check.isBlank()) {
            JsonErrors.write(res, 409, "FIRM_CHECK_REQUIRED");
            return;
        }
        if (!check.strip().equals(String.valueOf(active))) {
            JsonErrors.write(res, 409, "FIRM_MISMATCH");
            return;
        }

        Map<String, Object> row = jdbc.queryForList("""
                select u.status as user_status, m.id as membership_id, m.role as role, m.status as member_status,
                       f.active as firm_active, aa.effective_status as account_status
                  from app_user u
                  left join firm_member m on m.user_id = u.id and m.firm_id = ?
                  left join firm f on f.id = m.firm_id
                  left join firm_member o on o.firm_id = m.firm_id and o.role = 'OWNER'
                  left join account_access aa on aa.owner_user_id = o.user_id
                 where u.id = ?""", active, user.id()).stream().findFirst().orElse(null);

        if (row == null || !"ACTIVE".equals(row.get("user_status"))) {
            // the person no longer exists or was disabled: end the session
            session.invalidate();
            SecurityContextHolder.clearContext();
            JsonErrors.write(res, 401, "UNAUTHENTICATED");
            return;
        }
        if (row.get("membership_id") == null || !"ACTIVE".equals(row.get("member_status"))) {
            JsonErrors.write(res, 403, "NO_MEMBERSHIP");
            return;
        }
        if (!Boolean.TRUE.equals(row.get("firm_active"))) {
            JsonErrors.write(res, 403, "FIRM_INACTIVE");
            return;
        }
        Object account = row.get("account_status");
        if (account == null || !ALLOWED_ACCOUNT_STATES.contains(account)) {
            JsonErrors.write(res, 403, "ACCOUNT_BLOCKED");
            return;
        }

        req.setAttribute(FirmContext.ATTRIBUTE, new FirmContext(active, user.id(), ((Number) row.get("membership_id")).longValue(),
                MemberRole.valueOf((String) row.get("role"))));
        chain.doFilter(req, res);
    }
}
