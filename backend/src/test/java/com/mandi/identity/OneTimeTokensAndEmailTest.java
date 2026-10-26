package com.mandi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OneTimeTokensAndEmailTest {

    // ---- tokens ------------------------------------------------------------------------------------------

    @Test
    void aTokenIs256BitsAndOnlyItsSha256DigestIsMeantToBeStored() throws Exception {
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        byte[] raw = Base64.getUrlDecoder().decode(t.raw());
        assertThat(raw).hasSize(32);
        assertThat(t.raw()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(t.hash()).hasSize(32).isEqualTo(MessageDigest.getInstance("SHA-256").digest(raw));
        assertThat(t.hash()).isNotEqualTo(raw);
    }

    @Test
    void thePresentedTokenHashesToTheStoredDigestAndGarbageNeverDoes() {
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        assertThat(OneTimeTokens.hashOf(t.raw())).hasValueSatisfying(h -> assertThat(h).isEqualTo(t.hash()));
        for (String bad : new String[] {null, "", "short", t.raw() + "A", t.raw().substring(1), "!".repeat(43),
                t.raw().replace(t.raw().charAt(0), '*'), " ".repeat(43)}) {
            assertThat(OneTimeTokens.hashOf(bad)).as("rejected: " + bad).isEmpty();
        }
    }

    @Test
    void tokensAreUniqueAndTheirDescriptionHidesTheSecret() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) assertThat(seen.add(OneTimeTokens.issue().raw())).isTrue();
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        assertThat(t.toString()).doesNotContain(t.raw());
    }

    // ---- email -------------------------------------------------------------------------------------------

    @Test
    void emailsAreTrimmedNormalizedAndLowerCased() {
        assertThat(EmailNormalizer.normalize("  Owner@Example.TEST ")).isEqualTo("owner@example.test");
        assertThat(EmailNormalizer.normalize("a.b+tag@Sub.Example.Org")).isEqualTo("a.b+tag@sub.example.org");
        // NFKC folds the full-width form to ASCII
        assertThat(EmailNormalizer.normalize("ｏｗｎｅｒ@example.test")).isEqualTo("owner@example.test");
    }

    @Test
    void thingsThatAreNotEmailsAreRejected() {
        for (String bad : new String[] {null, "", "   ", "no-at-sign", "@example.test", "owner@", "owner@@example.test",
                "owner@example", "own er@example.test", "owner@exa mple.test", "owner@example..", "owner@.example.test",
                "ओनर@example.test", "owner@" + "a".repeat(260) + ".test", "a\u0000b@example.test"}) {
            assertThatThrownBy(() -> EmailNormalizer.normalize(bad)).as("rejected: " + bad)
                    .isInstanceOf(EmailNormalizer.InvalidEmailException.class);
        }
    }

    @Test
    void theNormalizedFormSatisfiesTheDatabaseRule() {
        String n = EmailNormalizer.normalize("  MiXeD@Example.Test ");
        assertThat(n).isEqualTo(n.toLowerCase()).isEqualTo(n.strip());
        assertThat(n.length()).isLessThanOrEqualTo(254);
    }
}
