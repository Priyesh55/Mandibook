package com.mandi.identity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A password-reset or email-verification token. Only the SHA-256 digest of the emailed 256-bit token exists here
 * (see {@link OneTimeTokens}); the raw token is never stored or logged. Consume it with
 * {@link CredentialTokenRepository#consume}, which is atomic.
 */
@Entity
@Table(name = "credential_token")
public class CredentialToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "requested_ip_hash", updatable = false)
    private byte[] requestedIpHash;

    protected CredentialToken() {
    }

    public CredentialToken(Long userId, TokenPurpose purpose, byte[] tokenHash, Instant expiresAt, byte[] requestedIpHash) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash.clone();
        this.expiresAt = expiresAt;
        this.requestedIpHash = requestedIpHash == null ? null : requestedIpHash.clone();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public TokenPurpose getPurpose() { return purpose; }
    public byte[] getTokenHash() { return tokenHash.clone(); }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getSupersededAt() { return supersededAt; }
    public Instant getCreatedAt() { return createdAt; }
}
