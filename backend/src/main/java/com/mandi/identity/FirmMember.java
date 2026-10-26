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
 * The only source of authorization to a firm: who may act in which firm and as what. Firm-owned, so it is loaded by id
 * only through {@code ...AndFirmId}. Never deleted. The platform administrator can never be a member: users come
 * from {@link AppUser}, and the platform administrator lives in another schema.
 */
@Entity
@Table(name = "firm_member")
public class FirmMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Column(name = "created_by_user_id", updatable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "deactivated_by_user_id")
    private Long deactivatedByUserId;

    protected FirmMember() {
    }

    public FirmMember(Long firmId, Long userId, MemberRole role, Long createdByUserId) {
        this.firmId = firmId;
        this.userId = userId;
        this.role = role;
        this.createdByUserId = createdByUserId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** ACTIVE to INACTIVE. Idempotent. */
    public void deactivate(Long byUserId, Instant at) {
        if (status == MemberStatus.INACTIVE) return;
        status = MemberStatus.INACTIVE;
        deactivatedAt = at;
        deactivatedByUserId = byUserId;
    }

    /** INACTIVE to ACTIVE (allowed). The deactivation record is cleared; the audit history keeps the fact. */
    public void reactivate() {
        status = MemberStatus.ACTIVE;
        deactivatedAt = null;
        deactivatedByUserId = null;
    }

    public boolean isActive() { return status == MemberStatus.ACTIVE; }

    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    public Long getUserId() { return userId; }
    public MemberRole getRole() { return role; }
    public MemberStatus getStatus() { return status; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
    public Long getDeactivatedByUserId() { return deactivatedByUserId; }
}
