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
 * Invitation of one person to one firm as an EMPLOYEE, made by a membership of that same firm (the database enforces
 * it with a composite foreign key). Firm-owned: load by id only through {@code ...AndFirmId}; the lookup by token digest
 * exists because the invitee arrives with a token, not a firm. Only the digest of the token is stored.
 */
@Entity
@Table(name = "firm_invitation")
public class FirmInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private MemberRole role = MemberRole.EMPLOYEE;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "invited_by_membership_id", nullable = false, updatable = false)
    private Long invitedByMembershipId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by_user_id")
    private Long acceptedByUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FirmInvitation() {
    }

    public FirmInvitation(Long firmId, String normalizedEmail, byte[] tokenHash, Long invitedByMembershipId, Instant expiresAt) {
        this.firmId = firmId;
        this.email = normalizedEmail;
        this.tokenHash = tokenHash.clone();
        this.invitedByMembershipId = invitedByMembershipId;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void revoke(Instant at) {
        if (acceptedAt == null && revokedAt == null) revokedAt = at;
    }

    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    public String getEmail() { return email; }
    public MemberRole getRole() { return role; }
    public byte[] getTokenHash() { return tokenHash.clone(); }
    public Long getInvitedByMembershipId() { return invitedByMembershipId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Long getAcceptedByUserId() { return acceptedByUserId; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
