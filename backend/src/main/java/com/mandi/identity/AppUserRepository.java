package com.mandi.identity;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** {@code email} must be normalized with {@link EmailNormalizer}. */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
