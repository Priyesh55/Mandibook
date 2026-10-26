package com.mandi.identity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Hashing and checking passwords (blueprint 6.2, decisions memo 10.2).
 *
 * <p>BCrypt only looks at the first 72 BYTES of a password. Spring's encoder refuses a longer password when encoding,
 * but when MATCHING it silently truncates: a 73-character attempt matches the hash of its own first 72 characters
 * (measured, memo 10.2). So the limit is enforced here on BOTH paths, before BCrypt is called:
 * {@link #hash} throws {@link InvalidPasswordException}; {@link #matches} answers false for anything over the limit.
 * The limit counts UTF-8 bytes, so a Devanagari password is limited to 24 characters.
 *
 * <p>Hashes are stored with the delegating encoder's {@code {bcrypt}} prefix so the algorithm can change later.
 */
@Service
public class PasswordService {

    /** BCrypt's hard input limit in bytes. */
    public static final int MAX_BYTES = 72;
    /** A cost below this is refused outside tests (blueprint RD-5 proposes 12; to be measured on the target machine). */
    static final int MIN_COST = 10;

    private final PasswordEncoder encoder;
    private final int minLength;

    @Autowired
    public PasswordService(@Value("${mandi.security.bcrypt-cost:12}") int cost,
                           @Value("${mandi.security.password-min-length:10}") int minLength) {
        this(cost, minLength, MIN_COST);
    }

    private PasswordService(int cost, int minLength, int lowestCost) {
        if (cost < lowestCost || cost > 20) {
            throw new IllegalArgumentException("mandi.security.bcrypt-cost must be between " + lowestCost + " and 20");
        }
        if (minLength < 1) {
            throw new IllegalArgumentException("mandi.security.password-min-length must be at least 1");
        }
        this.encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(cost)));
        this.minLength = minLength;
    }

    /** A cheap instance for tests of the byte rules. Never used by the application. */
    static PasswordService withCostForTests(int cost, int minLength) {
        return new PasswordService(cost, minLength, 4);
    }

    /** UTF-8 length, the unit BCrypt counts in. */
    public static int byteLength(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Rules for a NEW password.
     * The minimum length is provisional (blueprint: exact values still to be decided) and counts characters, not bytes.
     */
    public void validateForSet(String password) {
        if (password == null || password.isBlank()) throw new InvalidPasswordException(InvalidPasswordException.Reason.EMPTY);
        if (password.indexOf('\0') >= 0) throw new InvalidPasswordException(InvalidPasswordException.Reason.CONTAINS_NUL);
        if (byteLength(password) > MAX_BYTES) throw new InvalidPasswordException(InvalidPasswordException.Reason.TOO_LONG_BYTES);
        if (password.codePointCount(0, password.length()) < minLength) {
            throw new InvalidPasswordException(InvalidPasswordException.Reason.TOO_SHORT);
        }
    }

    /** @throws InvalidPasswordException when the password may not be set */
    public String hash(String password) {
        validateForSet(password);
        return encoder.encode(password);
    }

    /**
     * Checks an attempt against a stored hash. Anything BCrypt could not see in full (over 72 bytes), a NUL, null or
     * empty input, or an unusable stored hash is simply "no match": such a candidate can never be the password that was
     * hashed, because {@link #hash} refused it.
     */
    public boolean matches(String candidate, String storedHash) {
        if (candidate == null || candidate.isEmpty() || storedHash == null || storedHash.isEmpty()) return false;
        if (candidate.indexOf('\0') >= 0 || byteLength(candidate) > MAX_BYTES) return false;
        try {
            return encoder.matches(candidate, storedHash);
        } catch (IllegalArgumentException malformedHash) {
            return false;
        }
    }

    /** True when the stored hash uses an older cost or algorithm and should be replaced at the next successful login. */
    public boolean needsRehash(String storedHash) {
        return encoder.upgradeEncoding(storedHash);
    }
}
