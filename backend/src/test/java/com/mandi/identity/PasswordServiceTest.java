package com.mandi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.mandi.identity.InvalidPasswordException.Reason;

/**
 * The 72-byte rule on BOTH paths. Spring's BCrypt encoder refuses a longer password when ENCODING but silently
 * truncates when MATCHING (measured in docs/AUTHENTICATION_SECURITY_DECISIONS.md 10.2); these tests keep that from
 * ever becoming a way in. Synthetic passwords only. Cost 4 is used for speed, except in the one test that checks the
 * production cost.
 */
class PasswordServiceTest {

    private final PasswordService svc = PasswordService.withCostForTests(4, 10);

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    // ---- the limit counts UTF-8 bytes ---------------------------------------------------------------

    @Test
    void byteLengthCountsUtf8BytesNotCharacters() {
        assertThat(PasswordService.byteLength("abc")).isEqualTo(3);
        assertThat(PasswordService.byteLength("é")).isEqualTo(2);            // 2 bytes
        assertThat(PasswordService.byteLength("क")).isEqualTo(3);            // Devanagari: 3 bytes
        assertThat(PasswordService.byteLength("😀")).isEqualTo(4);           // supplementary: 4 bytes
        assertThat(PasswordService.MAX_BYTES).isEqualTo(72);
    }

    @Test
    void passwordsExactlyAtTheLimitAreAcceptedWhateverTheScript() {
        for (String atLimit : new String[] {
                repeat("a", 72),                  // 72 x 1 byte
                repeat("é", 36),                  // 36 x 2 bytes
                repeat("क", 24),                  // 24 x 3 bytes (24 Devanagari characters)
                repeat("😀", 18),                 // 18 x 4 bytes
                repeat("a", 30) + repeat("क", 14)  // 30 + 42 = 72 mixed
        }) {
            assertThat(PasswordService.byteLength(atLimit)).as(atLimit).isEqualTo(72);
            String hash = svc.hash(atLimit);
            assertThat(svc.matches(atLimit, hash)).as("at the limit: " + atLimit).isTrue();
        }
    }

    @Test
    void passwordsOverTheLimitAreRefusedWhenSet() {
        for (String over : new String[] {
                repeat("a", 73),
                repeat("é", 37),                  // 74 bytes
                repeat("क", 25),                  // 75 bytes
                repeat("😀", 19),                 // 76 bytes
                repeat("a", 70) + "क"             // 73 bytes: one multibyte character crosses the line
        }) {
            assertThat(PasswordService.byteLength(over)).isGreaterThan(72);
            assertThatThrownBy(() -> svc.hash(over)).as(over).isInstanceOf(InvalidPasswordException.class)
                    .extracting(e -> ((InvalidPasswordException) e).reason()).isEqualTo(Reason.TOO_LONG_BYTES);
        }
    }

    // ---- the truncation hazard, on the verification path -----------------------------------------------

    @Test
    void anOverLongCandidateNeverMatchesTheHashOfItsOwnFirst72Bytes() {
        String stored72 = repeat("p", 72);
        String hash = svc.hash(stored72);
        String candidate73 = stored72 + "x";

        // The premise (this is WHY the rule exists): Spring's BCrypt matches a truncated candidate. If a future Spring
        // version fixes this, this assertion fails and the rule can be revisited; the service must be safe either way.
        String rawBcrypt = new BCryptPasswordEncoder(4).encode(stored72);
        assertThat(new BCryptPasswordEncoder(4).matches(candidate73, rawBcrypt))
                .as("the library silently truncates on matching (premise of the rule)").isTrue();

        assertThat(svc.matches(stored72, hash)).isTrue();
        assertThat(svc.matches(candidate73, hash)).as("73 bytes must not match the 72-byte password").isFalse();
        assertThat(svc.matches(stored72 + repeat("y", 500), hash)).isFalse();
    }

    @Test
    void theSameHoldsForMultibytePasswordsWhereTheCutFallsInsideACharacter() {
        String hindi24 = repeat("क", 24);               // exactly 72 bytes
        String hash = svc.hash(hindi24);
        String longer = hindi24 + "ख";                   // 75 bytes, first 72 bytes identical
        assertThat(new BCryptPasswordEncoder(4).matches(longer, new BCryptPasswordEncoder(4).encode(hindi24)))
                .as("library premise").isTrue();
        assertThat(svc.matches(hindi24, hash)).isTrue();
        assertThat(svc.matches(longer, hash)).isFalse();

        // 71 bytes of 'a' then a 3-byte character: the cut would land inside the character
        String stored = repeat("a", 71) + "b";          // 72 bytes
        String h2 = svc.hash(stored);
        assertThat(svc.matches(repeat("a", 71) + "bक", h2)).isFalse();
    }

    @Test
    void aShorterOrDifferentPasswordStillDoesNotMatch() {
        String hash = svc.hash("correct horse battery");
        assertThat(svc.matches("correct horse batter", hash)).isFalse();
        assertThat(svc.matches("Correct horse battery", hash)).isFalse();
        assertThat(svc.matches("correct horse battery", hash)).isTrue();
    }

    @Test
    void emptyNullNulAndMalformedInputsAreNoMatchAndNeverThrow() {
        String hash = svc.hash("correct horse battery");
        assertThat(svc.matches(null, hash)).isFalse();
        assertThat(svc.matches("", hash)).isFalse();
        assertThat(svc.matches("correct horse battery", null)).isFalse();
        assertThat(svc.matches("correct horse battery", "")).isFalse();
        assertThat(svc.matches("correct horse battery", "not-a-hash")).isFalse();
        assertThat(svc.matches("correct horse battery", "{bcrypt}garbage")).isFalse();
        assertThat(svc.matches("correct horse\0battery", hash)).isFalse();
    }

    // ---- rules for setting ---------------------------------------------------------------------------

    @Test
    void setRulesRejectEmptyBlankNulAndTooShort() {
        assertThatThrownBy(() -> svc.hash(null)).isInstanceOf(InvalidPasswordException.class)
                .extracting(e -> ((InvalidPasswordException) e).reason()).isEqualTo(Reason.EMPTY);
        assertThatThrownBy(() -> svc.hash("   ")).extracting(e -> ((InvalidPasswordException) e).reason()).isEqualTo(Reason.EMPTY);
        assertThatThrownBy(() -> svc.hash("abcdefgh\0ij")).extracting(e -> ((InvalidPasswordException) e).reason())
                .isEqualTo(Reason.CONTAINS_NUL);
        assertThatThrownBy(() -> svc.hash("123456789")).extracting(e -> ((InvalidPasswordException) e).reason())
                .isEqualTo(Reason.TOO_SHORT);
        assertThat(svc.hash("1234567890")).startsWith("{bcrypt}");
    }

    @Test
    void minimumLengthCountsCharactersNotBytes() {
        assertThat(svc.hash(repeat("क", 10))).startsWith("{bcrypt}");          // 10 characters, 30 bytes
        assertThatThrownBy(() -> svc.hash(repeat("क", 9))).extracting(e -> ((InvalidPasswordException) e).reason())
                .isEqualTo(Reason.TOO_SHORT);
    }

    @Test
    void theExceptionNeverCarriesThePassword() {
        String secret = repeat("s3cret", 20);
        assertThatThrownBy(() -> svc.hash(secret)).hasMessageNotContaining("s3cret").hasMessage("password.tooLongBytes");
    }

    // ---- storage format and cost -----------------------------------------------------------------------

    @Test
    void hashesAreDelegatingBcryptAndNeverContainThePlaintext() {
        String password = "plaintext-marker-1234";
        String h = svc.hash(password);
        assertThat(h).startsWith("{bcrypt}$2").doesNotContain(password).doesNotContain("plaintext");
        assertThat(h.length()).isLessThanOrEqualTo(100);        // app_user.password_hash is varchar(100)
        assertThat(svc.hash(password)).as("salted: two hashes of one password differ").isNotEqualTo(h);
    }

    @Test
    void theProductionCostIs12AndLowerCostsAreRefusedOutsideTests() {
        PasswordService prod = new PasswordService(12, 10);
        String h = prod.hash("production-cost-check");
        assertThat(h).startsWith("{bcrypt}$2a$12$");
        assertThat(prod.matches("production-cost-check", h)).isTrue();
        assertThat(prod.needsRehash(h)).isFalse();
        assertThat(prod.needsRehash(svc.hash("production-cost-check"))).as("a cost-4 hash is upgraded at next login").isTrue();
        assertThatThrownBy(() -> new PasswordService(9, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordService(4, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordService(12, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
