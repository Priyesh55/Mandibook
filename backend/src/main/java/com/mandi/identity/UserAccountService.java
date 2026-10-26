package com.mandi.identity;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates identities. There is no endpoint yet: sign-in, invitations and the platform console come with later
 * slices. Authentication events (auth_event) are not part of this slice either.
 */
@Service
public class UserAccountService {

    private final AppUserRepository users;
    private final PasswordService passwords;

    public UserAccountService(AppUserRepository users, PasswordService passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    /**
     * Creates a user from a raw email and password: the email is normalized, the password is validated (72-byte rule
     * included) and hashed, and a second account with the same normalized email is refused, also when two requests race.
     *
     * @throws EmailNormalizer.InvalidEmailException  not an acceptable address
     * @throws InvalidPasswordException               the password may not be set
     * @throws DuplicateEmailException                the email is already registered
     */
    @Transactional
    public AppUser create(String email, String displayName, String rawPassword) {
        String normalized = EmailNormalizer.normalize(email);
        String hash = passwords.hash(rawPassword);
        if (users.existsByEmail(normalized)) throw new DuplicateEmailException();
        try {
            return users.saveAndFlush(new AppUser(normalized, displayName, hash));
        } catch (DataIntegrityViolationException race) {
            // the unique constraint is the real guard; the check above only gives the common case a clean path
            throw new DuplicateEmailException();
        }
    }
}
