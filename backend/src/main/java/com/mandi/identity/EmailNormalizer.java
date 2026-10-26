package com.mandi.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one way an email address is turned into the form stored and compared: trimmed, Unicode NFKC, lower-cased.
 * The database independently refuses anything not trimmed and lower-case ({@code app_user_email_form_ck}).
 *
 * <p>Provisional technical rule (not a business rule): only printable ASCII addresses are accepted. PostgreSQL's
 * {@code lower()} and Java's lower-casing can disagree for some non-ASCII letters, which would make the same
 * person two accounts or fail the database check; allowing internationalized addresses needs its own decision.
 */
public final class EmailNormalizer {

    public static final int MAX_LENGTH = 254;
    // one '@', non-empty local part and a dotted domain, printable ASCII without spaces
    private static final Pattern SHAPE = Pattern.compile("^[\\x21-\\x7E&&[^@]]+@[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z0-9-]+$");

    private EmailNormalizer() {
    }

    /** @throws InvalidEmailException when the text cannot be an email address */
    public static String normalize(String raw) {
        if (raw == null) throw new InvalidEmailException("email is required");
        String s = Normalizer.normalize(raw.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > MAX_LENGTH) throw new InvalidEmailException("email length is not acceptable");
        if (!SHAPE.matcher(s).matches()) throw new InvalidEmailException("email address is not valid");
        return s;
    }

    public static class InvalidEmailException extends IllegalArgumentException {
        public InvalidEmailException(String message) {
            super(message);
        }
    }
}
