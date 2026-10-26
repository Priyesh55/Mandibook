package com.mandi.identity;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CredentialTokenRepository extends JpaRepository<CredentialToken, Long> {

    Optional<CredentialToken> findByTokenHash(byte[] tokenHash);

    /**
     * Consumes a token exactly once, even under concurrency: the single UPDATE succeeds for one caller only.
     * Returns the number of rows changed (1 = the token was valid and is now consumed, 0 = unknown, used, replaced or expired).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CredentialToken t set t.consumedAt = :now where t.tokenHash = :hash and t.purpose = :purpose "
            + "and t.consumedAt is null and t.supersededAt is null and t.expiresAt > :now")
    int consume(@Param("hash") byte[] hash, @Param("purpose") TokenPurpose purpose, @Param("now") Instant now);

    /** A newly issued token replaces the user's earlier unused ones of the same purpose. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CredentialToken t set t.supersededAt = :now where t.userId = :userId and t.purpose = :purpose "
            + "and t.consumedAt is null and t.supersededAt is null")
    int supersedeOpen(@Param("userId") Long userId, @Param("purpose") TokenPurpose purpose, @Param("now") Instant now);
}
