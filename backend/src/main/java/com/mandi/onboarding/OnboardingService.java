package com.mandi.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.audit.AuditService;
import com.mandi.firm.FirmContext;
import com.mandi.identity.AppUser;
import com.mandi.identity.AppUserRepository;
import com.mandi.identity.DuplicateEmailException;
import com.mandi.identity.EmailNormalizer;
import com.mandi.identity.FirmInvitation;
import com.mandi.identity.FirmInvitationRepository;
import com.mandi.identity.FirmMember;
import com.mandi.identity.FirmMemberRepository;
import com.mandi.identity.MemberRole;
import com.mandi.identity.OneTimeTokens;
import com.mandi.identity.UserAccountService;
import com.mandi.security.AuthenticatedUser;

/**
 * The only ways a business identity or a firm membership comes into existence (blueprint 4.5, 7). Nobody can give
 * themselves a role, a firm or another owner's firm:
 * <ul>
 *   <li>an EMPLOYEE membership is created only by accepting an invitation that an OWNER of that firm issued, with a token
 *       that only the invited mailbox received (7 days, single use, only the SHA-256 digest is stored);</li>
 *   <li>an owner exists only through an owner invitation made in the platform schema; accepting it sets the owner's own
 *       password and links the account through a narrow SECURITY DEFINER function (V9);</li>
 *   <li>a firm is created only by a person with an owner account that allows access, who becomes its single OWNER.</li>
 * </ul>
 * There is no self-registration. Passwords are set by their owner and never seen by anybody else.
 */
@Service
public class OnboardingService {

    static final Duration INVITATION_LIFETIME = Duration.ofDays(7);

    private final AppUserRepository users;
    private final FirmMemberRepository members;
    private final FirmInvitationRepository invitations;
    private final UserAccountService accounts;
    private final InvitationMailer mailer;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final String publicBaseUrl;

    public OnboardingService(AppUserRepository users, FirmMemberRepository members, FirmInvitationRepository invitations,
                             UserAccountService accounts, InvitationMailer mailer, AuditService audit, DataSource dataSource,
                             @Value("${mandi.public-base-url:}") String publicBaseUrl) {
        this.users = users;
        this.members = members;
        this.invitations = invitations;
        this.accounts = accounts;
        this.mailer = mailer;
        this.audit = audit;
        this.jdbc = new JdbcTemplate(dataSource);
        this.publicBaseUrl = publicBaseUrl;
    }

    // ---- owner of a firm: invitations and members (firm context required, role OWNER) ------------------------------------

    private static void requireOwner(FirmContext ctx) {
        if (ctx.role() != MemberRole.OWNER) throw new OnboardingException(403, "PERMISSION_DENIED");
    }

    public record InvitationView(long id, String email, Instant expiresAt, Instant createdAt) {
    }

    @Transactional
    public InvitationView inviteEmployee(FirmContext ctx, String rawEmail) {
        requireOwner(ctx);
        String email = EmailNormalizer.normalize(rawEmail);
        Optional<AppUser> existing = users.findByEmail(email);
        if (existing.isPresent() && members.findByFirmIdAndUserId(ctx.firmId(), existing.get().getId()).isPresent()) {
            throw new OnboardingException(409, "ALREADY_MEMBER");
        }
        if (publicBaseUrl.isBlank()) throw new OnboardingException(503, "EMAIL_NOT_CONFIGURED");
        // a resend replaces the earlier open invitation of the same firm and email
        jdbc.update("update firm_invitation set revoked_at = now() where firm_id = ? and email = ? and accepted_at is null and revoked_at is null",
                ctx.firmId(), email);
        OneTimeTokens.Issued token = OneTimeTokens.issue();
        Instant expires = Instant.now().plus(INVITATION_LIFETIME);
        FirmInvitation saved = invitations.saveAndFlush(new FirmInvitation(ctx.firmId(), email, token.hash(), ctx.membershipId(), expires));
        String firmName = jdbc.queryForObject("select name from firm where id = ?", String.class, ctx.firmId());
        try {
            // the token leaves this method only inside the link given to the mail port
            mailer.sendInvitation(email, "EMPLOYEE", firmName, publicBaseUrl + "/accept-invitation#token=" + token.raw(), expires);
        } catch (InvitationMailer.MailNotConfiguredException notConfigured) {
            throw new OnboardingException(503, "EMAIL_NOT_CONFIGURED");     // rolls back: nothing is stored that nobody can receive
        }
        audit.record("FIRM_INVITATION", saved.getId(), "CREATED", "employee invitation by user " + ctx.userId());
        return new InvitationView(saved.getId(), email, expires, saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingInvitations(FirmContext ctx) {
        requireOwner(ctx);
        return jdbc.queryForList("select id, email, expires_at as \"expiresAt\", created_at as \"createdAt\" from firm_invitation "
                + "where firm_id = ? and accepted_at is null and revoked_at is null and expires_at > now() order by id desc", ctx.firmId());
    }

    @Transactional
    public void revokeInvitation(FirmContext ctx, long invitationId) {
        requireOwner(ctx);
        FirmInvitation inv = invitations.findByIdAndFirmId(invitationId, ctx.firmId()).orElseThrow(() -> new OnboardingException(404, "NOT_FOUND"));
        jdbc.update("update firm_invitation set revoked_at = now() where id = ? and firm_id = ? and accepted_at is null and revoked_at is null", inv.getId(), ctx.firmId());
        audit.record("FIRM_INVITATION", inv.getId(), "REVOKED", "by user " + ctx.userId());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(FirmContext ctx) {
        requireOwner(ctx);
        return jdbc.queryForList("select m.id, m.role, m.status, u.email, u.display_name as \"displayName\", m.created_at as \"createdAt\" "
                + "from firm_member m join app_user u on u.id = m.user_id where m.firm_id = ? order by (m.role = 'OWNER') desc, lower(u.email)", ctx.firmId());
    }

    /** Deactivates or reactivates an EMPLOYEE membership of the active firm. The OWNER membership cannot be changed here. */
    @Transactional
    public void setMemberActive(FirmContext ctx, long membershipId, boolean active) {
        requireOwner(ctx);
        FirmMember m = members.findByIdAndFirmId(membershipId, ctx.firmId()).orElseThrow(() -> new OnboardingException(404, "NOT_FOUND"));
        if (m.getRole() == MemberRole.OWNER) throw new OnboardingException(422, "CANNOT_CHANGE_OWNER");
        if (active) m.reactivate(); else m.deactivate(ctx.userId(), Instant.now());
        members.saveAndFlush(m);
        audit.record("FIRM_MEMBER", m.getId(), active ? "ACTIVATED" : "DEACTIVATED", "by user " + ctx.userId());
    }

    // ---- accepting an invitation ----------------------------------------------------------------------------------------------

    /**
     * An invited person without an account sets a password and the account is created. The result tells the page which
     * kind of invitation it was. Every failure of the token (unknown, used, expired, revoked) is the same INVITATION_INVALID.
     * Everything happens in one transaction: if any step fails, no account exists afterwards.
     */
    @Transactional
    public String accept(String rawToken, String displayName, String password) {
        byte[] hash = OneTimeTokens.hashOf(rawToken).orElseThrow(() -> new OnboardingException(400, "INVITATION_INVALID"));
        Optional<FirmInvitation> employee = invitations.findByTokenHash(hash);
        if (employee.isPresent()) {
            FirmInvitation inv = employee.get();
            // a used, revoked or expired invitation is just invalid; only an OPEN one may say that the account exists
            Integer open = jdbc.queryForObject("select count(*) from firm_invitation where id = ? and accepted_at is null and revoked_at is null and expires_at > now()", Integer.class, inv.getId());
            if (open == null || open == 0) throw new OnboardingException(400, "INVITATION_INVALID");
            if (users.existsByEmail(inv.getEmail())) throw new OnboardingException(409, "ACCOUNT_EXISTS");
            AppUser user = createUser(inv.getEmail(), displayName, password);
            Long inviter = members.findByIdAndFirmId(inv.getInvitedByMembershipId(), inv.getFirmId()).map(FirmMember::getUserId).orElse(null);
            consumeEmployeeInvitation(inv.getId(), user.getId());
            members.saveAndFlush(new FirmMember(inv.getFirmId(), user.getId(), MemberRole.EMPLOYEE, inviter));
            return "EMPLOYEE";
        }
        List<Map<String, Object>> owner = jdbc.queryForList("select owner_account_id, email from public.peek_owner_invitation(?)", hash);
        if (owner.isEmpty()) throw new OnboardingException(400, "INVITATION_INVALID");
        String email = (String) owner.get(0).get("email");
        if (users.existsByEmail(email)) throw new OnboardingException(409, "ACCOUNT_EXISTS");
        AppUser user = createUser(email, displayName, password);
        try {
            jdbc.queryForObject("select public.accept_owner_invitation(?, ?)", Long.class, hash, user.getId());
        } catch (DataAccessException refused) {
            throw new OnboardingException(400, "INVITATION_INVALID");        // rolls the new user back
        }
        return "OWNER";
    }

    /** An EXISTING account accepts an employee invitation while signed in; the invitation must be for the signed-in email. */
    @Transactional
    public void acceptSignedIn(AuthenticatedUser signedIn, String rawToken) {
        byte[] hash = OneTimeTokens.hashOf(rawToken).orElseThrow(() -> new OnboardingException(400, "INVITATION_INVALID"));
        FirmInvitation inv = invitations.findByTokenHash(hash).orElseThrow(() -> new OnboardingException(400, "INVITATION_INVALID"));
        if (!inv.getEmail().equals(signedIn.email())) throw new OnboardingException(400, "INVITATION_INVALID");
        if (members.findByFirmIdAndUserId(inv.getFirmId(), signedIn.id()).isPresent()) throw new OnboardingException(400, "INVITATION_INVALID");
        Long inviter = members.findByIdAndFirmId(inv.getInvitedByMembershipId(), inv.getFirmId()).map(FirmMember::getUserId).orElse(null);
        consumeEmployeeInvitation(inv.getId(), signedIn.id());
        members.saveAndFlush(new FirmMember(inv.getFirmId(), signedIn.id(), MemberRole.EMPLOYEE, inviter));
    }

    private AppUser createUser(String email, String displayName, String password) {
        try {
            String name = displayName == null || displayName.isBlank() ? null : displayName.strip();
            if (name != null && name.length() > 200) throw new OnboardingException(400, "INVALID_REQUEST");
            AppUser u = accounts.create(email, name, password);
            u.markEmailVerified(Instant.now());     // opening the emailed link proved control of the mailbox
            return users.saveAndFlush(u);
        } catch (DuplicateEmailException race) {
            throw new OnboardingException(409, "ACCOUNT_EXISTS");
        }
    }

    /** One atomic statement: of two simultaneous acceptances only one wins; the loser's whole transaction is rolled back. */
    private void consumeEmployeeInvitation(long invitationId, long userId) {
        int n = jdbc.update("update firm_invitation set accepted_at = now(), accepted_by_user_id = ? "
                + "where id = ? and accepted_at is null and revoked_at is null and expires_at > now()", userId, invitationId);
        if (n != 1) throw new OnboardingException(400, "INVITATION_INVALID");
    }

    // ---- creating a firm --------------------------------------------------------------------------------------------------------

    public record NewFirm(long id, String name, String nameHi) {
    }

    /** Expense kinds of a farmer bill, with their display order: name, Hindi name, position. */
    private static final String[][] STARTER_EXPENSE_HEADS = {
        {"Hammali", "हम्माली", "1"}, {"Tulai", "तुलाई", "2"}, {"Bharai", "भराई", "3"}, {"Khadi Karai", "खड़ी कराई", "4"},
        {"Medan Hammali", "मैदान हम्माली", "5"}, {"Motor Bhada", "मोटर भाड़ा", "6"}, {"Nagdi Maal", "नगदी माल", "7"},
        {"Pala Karai", "पाला कराई", "8"}, {"Other Exp.", "अन्य खर्च", "9"},
    };

    /**
     * A person with an owner account that allows access creates a firm and becomes its OWNER, with the three confirmed
     * starter items (assumption A11). Anybody else is refused: a business user cannot make themselves an owner.
     */
    @Transactional
    public NewFirm createFirm(AuthenticatedUser user, String rawName, String rawNameHi) {
        String name = rawName == null ? "" : rawName.strip();
        String nameHi = rawNameHi == null || rawNameHi.isBlank() ? null : rawNameHi.strip();
        if (name.isEmpty() || name.length() > 200 || (nameHi != null && nameHi.length() > 200)) throw new OnboardingException(400, "INVALID_REQUEST");
        List<String> status = jdbc.queryForList("select effective_status from public.account_access where owner_user_id = ?", String.class, user.id());
        if (status.isEmpty()) throw new OnboardingException(403, "NOT_AN_OWNER");
        if (!List.of("TRIAL", "ACTIVE").contains(status.get(0))) throw new OnboardingException(403, "ACCOUNT_BLOCKED");

        String code = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();     // opaque, never chosen by users
        Long firmId = jdbc.queryForObject("insert into firm (code, name, name_hi) values (?, ?, ?) returning id", Long.class, code, name, nameHi);
        members.saveAndFlush(new FirmMember(firmId, user.id(), MemberRole.OWNER, user.id()));
        jdbc.update("insert into item (name, name_hi, firm_id) values ('Onion', 'प्याज़', ?), ('Potato', 'आलू', ?), ('Garlic', 'लहसुन', ?)", firmId, firmId, firmId);
        // the expense kinds of a farmer bill (without any rate: the owner sets a per-piece rate on a head in Masters where it has one)
        for (String[] head : STARTER_EXPENSE_HEADS) {
            jdbc.update("insert into expense_head (name, name_hi, firm_id, sort_order) values (?, ?, ?, ?)", head[0], head[1], firmId, Integer.parseInt(head[2]));
        }
        Long membershipId = jdbc.queryForObject("select id from firm_member where firm_id = ? and user_id = ?", Long.class, firmId, user.id());
        jdbc.update("insert into audit_event (entity_type, entity_id, action, details, actor, firm_id, actor_user_id, actor_membership_id, actor_role) "
                + "values ('FIRM', ?, 'CREATED', ?, ?, ?, ?, ?, 'OWNER')", firmId, "firm created with 3 starter items and " + STARTER_EXPENSE_HEADS.length + " starter expense heads", "user:" + user.id(), firmId, user.id(), membershipId);
        return new NewFirm(firmId, name, nameHi);
    }
}
