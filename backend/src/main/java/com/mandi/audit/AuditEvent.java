package com.mandi.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One immutable history row. Only ever inserted; the database rejects UPDATE, DELETE and TRUNCATE. */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    @Column(name = "entity_type", nullable = false, length = 40, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private Long entityId;

    @Column(nullable = false, length = 30, updatable = false)
    private String action;

    @Column(length = 1000, updatable = false)
    private String details;

    @Column(nullable = false, length = 100, updatable = false)
    private String actor;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(name = "actor_membership_id", updatable = false)
    private Long actorMembershipId;

    @Column(name = "actor_role", length = 20, updatable = false)
    private String actorRole;

    @Column(name = "request_id", updatable = false)
    private java.util.UUID requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(Long firmId, String entityType, Long entityId, String action, String details, String actor) {
        this.firmId = firmId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.details = details == null || details.length() <= 1000 ? details : details.substring(0, 997) + "...";
        this.actor = actor;
    }

    /** An event of a signed-in person: who (user, membership in this firm, role at the time) and in which request. */
    public AuditEvent(Long firmId, String entityType, Long entityId, String action, String details, Long actorUserId,
                      Long actorMembershipId, String actorRole, java.util.UUID requestId) {
        this(firmId, entityType, entityId, action, details, "user:" + actorUserId);
        this.actorUserId = actorUserId;
        this.actorMembershipId = actorMembershipId;
        this.actorRole = actorRole;
        this.requestId = requestId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public String getActor() { return actor; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getActorUserId() { return actorUserId; }
    public Long getActorMembershipId() { return actorMembershipId; }
    public String getActorRole() { return actorRole; }
    public java.util.UUID getRequestId() { return requestId; }
}
