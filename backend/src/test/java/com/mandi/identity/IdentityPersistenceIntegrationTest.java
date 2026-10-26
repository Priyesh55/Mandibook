package com.mandi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mandi.TestcontainersConfiguration;

/**
 * The identity code against the real schema, as the restricted runtime role (the application's own connection).
 * Hibernate validates the entity mappings against V6 at start-up, so a mapping that drifts from the schema fails here.
 * Every test uses its own unique emails and firm codes (identity rows are never deleted); synthetic data only.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityPersistenceIntegrationTest {

    @Autowired UserAccountService accounts;
    @Autowired PasswordService passwords;
    @Autowired AppUserRepository users;
    @Autowired FirmMemberRepository members;
    @Autowired CredentialTokenRepository tokens;
    @Autowired FirmInvitationRepository invitations;
    /** The OWNER connection (fixtures only), see TestcontainersConfiguration. */
    @Autowired JdbcTemplate owner;

    private static String email(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private long newFirm() {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return owner.queryForObject("insert into firm (code, name) values (?, ?) returning id", Long.class, code, "Synthetic " + code);
    }

    private AppUser newUser(String tag) {
        return accounts.create(email(tag), "Synthetic " + tag, "a-long-enough-password");
    }

    // ---- accounts -----------------------------------------------------------------------------------------

    @Test
    void anAccountIsStoredNormalizedWithAHashAndNeverThePlaintext() {
        String password = "plaintext-marker-" + UUID.randomUUID();
        AppUser u = accounts.create("  Mixed.Case@Example.TEST ", "Mixed", password);
        String stored = owner.queryForObject("select password_hash from app_user where id = ?", String.class, u.getId());
        assertThat(u.getEmail()).isEqualTo("mixed.case@example.test");
        assertThat(stored).startsWith("{bcrypt}$2").doesNotContain(password);
        assertThat(passwords.matches(password, stored)).isTrue();
        assertThat(passwords.matches(password + "x", stored)).isFalse();
        // no column of the row holds the plaintext
        assertThat(owner.queryForObject("select count(*) from app_user where app_user::text like ?", Integer.class, "%" + password + "%")).isZero();
        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.getCreatedAt()).isNotNull();
        assertThat(u.getPasswordChangedAt()).isNotNull();
    }

    @Test
    void aSecondAccountWithTheSameEmailIsRefusedWhateverTheSpelling() {
        String e = email("dup");
        accounts.create(e, "First", "a-long-enough-password");
        for (String spelling : new String[] {e, e.toUpperCase(), "  " + e + "  ", e.replace("@", "@".repeat(1))}) {
            assertThatThrownBy(() -> accounts.create(spelling, "Second", "another-long-password")).as(spelling)
                    .isInstanceOf(DuplicateEmailException.class);
        }
        assertThat(owner.queryForObject("select count(*) from app_user where email = ?", Integer.class, e)).isEqualTo(1);
    }

    @Test
    void twoSimultaneousRegistrationsOfOneEmailProduceExactlyOneAccount() throws Exception {
        String e = email("race");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    accounts.create(e, "Racer", "a-long-enough-password");
                    return "created";
                } catch (DuplicateEmailException d) {
                    return "duplicate";
                }
            }));
        }
        go.countDown();
        int created = 0, duplicate = 0;
        for (Future<String> f : results) {
            String r = f.get();
            if (r.equals("created")) created++; else duplicate++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(duplicate).isEqualTo(threads - 1);
        assertThat(owner.queryForObject("select count(*) from app_user where email = ?", Integer.class, e)).isEqualTo(1);
    }

    @Test
    void badEmailsAndPasswordsNeverReachTheDatabase() {
        int before = owner.queryForObject("select count(*) from app_user", Integer.class);
        assertThatThrownBy(() -> accounts.create("not-an-email", "x", "a-long-enough-password")).isInstanceOf(EmailNormalizer.InvalidEmailException.class);
        assertThatThrownBy(() -> accounts.create(email("pw"), "x", "short")).isInstanceOf(InvalidPasswordException.class);
        assertThatThrownBy(() -> accounts.create(email("pw"), "x", "a".repeat(73))).isInstanceOf(InvalidPasswordException.class)
                .hasMessage("password.tooLongBytes");
        assertThatThrownBy(() -> accounts.create(email("pw"), "x", "क".repeat(25))).isInstanceOf(InvalidPasswordException.class);
        assertThat(owner.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(before);
        // a 72-byte password is accepted and verifies; a 73rd character does not
        String email = email("limit");
        accounts.create(email, "limit", "क".repeat(24));
        AppUser u = users.findByEmail(email).orElseThrow();
        assertThat(passwords.matches("क".repeat(24), u.getPasswordHash())).isTrue();
        assertThat(passwords.matches("क".repeat(24) + "क", u.getPasswordHash())).isFalse();
    }

    @Test
    void disablingAndChangingThePasswordArePersisted() {
        AppUser u = newUser("chg");
        String oldHash = u.getPasswordHash();
        u.disable();
        u.changePasswordHash(passwords.hash("a-brand-new-password"));
        users.saveAndFlush(u);
        AppUser again = users.findById(u.getId()).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(again.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(passwords.matches("a-brand-new-password", again.getPasswordHash())).isTrue();
        assertThat(passwords.matches("a-long-enough-password", again.getPasswordHash())).isFalse();
    }

    @Test
    void theRuntimeRoleCannotDeleteIdentities() {
        AppUser u = newUser("nodel");
        assertThatThrownBy(() -> users.deleteById(u.getId())).isInstanceOf(DataAccessException.class);
        assertThat(users.existsById(u.getId())).isTrue();
    }

    // ---- memberships ------------------------------------------------------------------------------------------

    @Test
    void anOwnerCanHaveManyFirmsAndAnEmployeeSeesOnlyTheAssignedOnes() {
        AppUser ownerUser = newUser("own"), employee = newUser("emp");
        long[] firms = {newFirm(), newFirm(), newFirm()};
        for (long f : firms) members.saveAndFlush(new FirmMember(f, ownerUser.getId(), MemberRole.OWNER, ownerUser.getId()));
        members.saveAndFlush(new FirmMember(firms[0], employee.getId(), MemberRole.EMPLOYEE, ownerUser.getId()));

        var ownerFirms = members.findByUserIdAndStatus(ownerUser.getId(), MemberStatus.ACTIVE, PageRequest.of(0, 2, Sort.by("id")));
        assertThat(ownerFirms.getTotalElements()).isEqualTo(3);
        assertThat(ownerFirms.getContent()).hasSize(2);                               // paged
        var employeeFirms = members.findByUserIdAndStatus(employee.getId(), MemberStatus.ACTIVE, PageRequest.of(0, 10));
        assertThat(employeeFirms.getContent()).extracting(FirmMember::getFirmId).containsExactly(firms[0]);
        assertThat(members.findByFirmIdAndUserId(firms[1], employee.getId())).as("no membership, no access").isEmpty();
    }

    @Test
    void membershipsAreLoadedByIdOnlyWithinTheirFirmAndOnlyOneOwnerPerFirmExists() {
        AppUser a = newUser("a"), b = newUser("b");
        long f = newFirm(), g = newFirm();
        FirmMember m = members.saveAndFlush(new FirmMember(f, a.getId(), MemberRole.OWNER, null));
        assertThat(members.findByIdAndFirmId(m.getId(), f)).isPresent();
        assertThat(members.findByIdAndFirmId(m.getId(), g)).as("another firm's id lookup").isEmpty();
        assertThatThrownBy(() -> members.saveAndFlush(new FirmMember(f, b.getId(), MemberRole.OWNER, null)))
                .as("a second owner of the same firm").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> members.saveAndFlush(new FirmMember(f, a.getId(), MemberRole.EMPLOYEE, null)))
                .as("the same person twice in a firm").isInstanceOf(DataAccessException.class);
    }

    @Test
    void deactivationAndReactivationArePersistedAndKeepTheRow() {
        AppUser o = newUser("o"), e = newUser("e");
        long f = newFirm();
        members.saveAndFlush(new FirmMember(f, o.getId(), MemberRole.OWNER, null));
        FirmMember m = members.saveAndFlush(new FirmMember(f, e.getId(), MemberRole.EMPLOYEE, o.getId()));
        Instant at = Instant.now();
        m.deactivate(o.getId(), at);
        members.saveAndFlush(m);
        FirmMember inactive = members.findByIdAndFirmId(m.getId(), f).orElseThrow();
        assertThat(inactive.getStatus()).isEqualTo(MemberStatus.INACTIVE);
        assertThat(inactive.getDeactivatedAt()).isNotNull();
        assertThat(inactive.getDeactivatedByUserId()).isEqualTo(o.getId());
        assertThat(members.findByUserIdAndStatus(e.getId(), MemberStatus.ACTIVE, PageRequest.of(0, 10)).getContent()).isEmpty();
        inactive.reactivate();
        members.saveAndFlush(inactive);
        FirmMember active = members.findByIdAndFirmId(m.getId(), f).orElseThrow();
        assertThat(active.isActive()).isTrue();
        assertThat(active.getDeactivatedAt()).isNull();
        assertThatThrownBy(() -> members.deleteById(m.getId())).as("rows are never deleted").isInstanceOf(DataAccessException.class);
    }

    // ---- tokens --------------------------------------------------------------------------------------------------

    @Test
    void aTokenIsStoredOnlyAsItsDigestAndNeverAsTheRawValue() {
        AppUser u = newUser("tk");
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        tokens.saveAndFlush(new CredentialToken(u.getId(), TokenPurpose.PASSWORD_RESET, t.hash(), Instant.now().plus(Duration.ofMinutes(30)), null));
        assertThat(owner.queryForObject("select count(*) from credential_token where credential_token::text like ?", Integer.class, "%" + t.raw() + "%"))
                .as("the raw token is nowhere in the row").isZero();
        assertThat(tokens.findByTokenHash(OneTimeTokens.hashOf(t.raw()).orElseThrow())).isPresent();
        assertThat(tokens.findByTokenHash(OneTimeTokens.issue().hash())).isEmpty();
    }

    @Test
    void aTokenCanBeConsumedExactlyOnceAndOnlyBeforeItExpiresForItsPurpose() {
        AppUser u = newUser("once");
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        Instant now = Instant.now();
        tokens.saveAndFlush(new CredentialToken(u.getId(), TokenPurpose.PASSWORD_RESET, t.hash(), now.plus(Duration.ofMinutes(30)), null));
        assertThat(tokens.consume(t.hash(), TokenPurpose.EMAIL_VERIFY, now)).as("wrong purpose").isZero();
        assertThat(tokens.consume(t.hash(), TokenPurpose.PASSWORD_RESET, now.plus(Duration.ofMinutes(31)))).as("after expiry").isZero();
        assertThat(tokens.consume(t.hash(), TokenPurpose.PASSWORD_RESET, now)).isEqualTo(1);
        assertThat(tokens.consume(t.hash(), TokenPurpose.PASSWORD_RESET, now)).as("second use").isZero();
        assertThat(tokens.consume(OneTimeTokens.issue().hash(), TokenPurpose.PASSWORD_RESET, now)).as("unknown token").isZero();
    }

    @Test
    void simultaneousUseOfOneTokenSucceedsForExactlyOneCaller() throws Exception {
        AppUser u = newUser("conc");
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        tokens.saveAndFlush(new CredentialToken(u.getId(), TokenPurpose.PASSWORD_RESET, t.hash(), Instant.now().plus(Duration.ofMinutes(30)), null));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return tokens.consume(t.hash(), TokenPurpose.PASSWORD_RESET, Instant.now());
            }));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> f : results) total += f.get();
        pool.shutdown();
        assertThat(total).as("exactly one winner").isEqualTo(1);
    }

    @Test
    void aNewTokenSupersedesTheEarlierUnusedOnes() {
        AppUser u = newUser("sup");
        OneTimeTokens.Issued first = OneTimeTokens.issue(), second = OneTimeTokens.issue();
        Instant now = Instant.now();
        tokens.saveAndFlush(new CredentialToken(u.getId(), TokenPurpose.PASSWORD_RESET, first.hash(), now.plus(Duration.ofMinutes(30)), null));
        assertThat(tokens.supersedeOpen(u.getId(), TokenPurpose.PASSWORD_RESET, now)).isEqualTo(1);
        tokens.saveAndFlush(new CredentialToken(u.getId(), TokenPurpose.PASSWORD_RESET, second.hash(), now.plus(Duration.ofMinutes(30)), null));
        assertThat(tokens.consume(first.hash(), TokenPurpose.PASSWORD_RESET, now)).as("replaced").isZero();
        assertThat(tokens.consume(second.hash(), TokenPurpose.PASSWORD_RESET, now)).isEqualTo(1);
    }

    @Test
    void invitationsStoreOnlyTheDigestAndAreFoundByItOrByIdWithinTheirFirm() {
        AppUser o = newUser("inv");
        long f = newFirm(), g = newFirm();
        FirmMember m = members.saveAndFlush(new FirmMember(f, o.getId(), MemberRole.OWNER, null));
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        FirmInvitation inv = invitations.saveAndFlush(new FirmInvitation(f, email("new"), t.hash(), m.getId(), Instant.now().plus(Duration.ofDays(7))));
        assertThat(invitations.findByTokenHash(OneTimeTokens.hashOf(t.raw()).orElseThrow())).isPresent();
        assertThat(invitations.findByIdAndFirmId(inv.getId(), f)).isPresent();
        assertThat(invitations.findByIdAndFirmId(inv.getId(), g)).as("another firm").isEmpty();
        assertThat(owner.queryForObject("select count(*) from firm_invitation where firm_invitation::text like ?", Integer.class, "%" + t.raw() + "%")).isZero();
        assertThat(inv.getRole()).isEqualTo(MemberRole.EMPLOYEE);
        // an invitation from a membership of another firm is refused by the database
        assertThatThrownBy(() -> invitations.saveAndFlush(new FirmInvitation(g, email("x"), OneTimeTokens.issue().hash(), m.getId(),
                Instant.now().plus(Duration.ofDays(7))))).isInstanceOf(DataAccessException.class);
    }
}
