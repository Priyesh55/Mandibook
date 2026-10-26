package com.mandi.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mandi.shared.Like;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Which firm the signed-in person is working in. The user is taken from the session, never from the request; the firm
 * is accepted only after an ACTIVE membership of that user in an active firm is found. The answer for a firm the user
 * has no membership in is the same whether or not the firm exists.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class FirmSelectionController {

    public record SelectFirmRequest(Long firmId) {
    }

    private final JdbcTemplate jdbc;

    public FirmSelectionController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The firms the person may use, paged and searchable (an owner has no firm-count limit). */
    @GetMapping("/firms")
    public ResponseEntity<Map<String, Object>> firms(Authentication authentication,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        }
        int s = Math.max(1, Math.min(size, 100));
        int p = Math.max(0, page);
        String like = q == null || q.isBlank() ? null : Like.contains(q);
        String filter = like == null ? "" : " and (lower(f.name) like ? escape '" + Like.ESCAPE + "' or lower(coalesce(f.name_hi, '')) like ? escape '" + Like.ESCAPE + "')";
        Object[] countArgs = like == null ? new Object[] {user.id()} : new Object[] {user.id(), like, like};
        Long total = jdbc.queryForObject("select count(*) from firm_member m join firm f on f.id = m.firm_id "
                + "where m.user_id = ? and m.status = 'ACTIVE' and f.active" + filter, Long.class, countArgs);
        Object[] pageArgs = like == null ? new Object[] {user.id(), s, (long) p * s} : new Object[] {user.id(), like, like, s, (long) p * s};
        List<Map<String, Object>> rows = jdbc.queryForList("select f.id, f.name, f.name_hi as \"nameHi\", m.role from firm_member m join firm f on f.id = m.firm_id "
                + "where m.user_id = ? and m.status = 'ACTIVE' and f.active" + filter + " order by lower(f.name), f.id limit ? offset ?", pageArgs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", p);
        out.put("size", s);
        out.put("totalElements", total);
        return ResponseEntity.ok(out);
    }

    /** Makes a firm the active firm of THIS session. Needs the CSRF token like every state change. */
    @PostMapping("/active-firm")
    public ResponseEntity<Map<String, Object>> select(Authentication authentication, @RequestBody(required = false) SelectFirmRequest body,
                                                      HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        }
        if (body == null || body.firmId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
        }
        List<Map<String, Object>> found = jdbc.queryForList("select f.id, f.name, f.name_hi as \"nameHi\", m.role from firm_member m join firm f on f.id = m.firm_id "
                + "where m.firm_id = ? and m.user_id = ? and m.status = 'ACTIVE' and f.active", body.firmId(), user.id());
        if (found.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "NO_MEMBERSHIP"));
        }
        session.setAttribute(FirmContextFilter.ACTIVE_FIRM, ((Number) found.get(0).get("id")).longValue());
        return ResponseEntity.ok(found.get(0));
    }
}
