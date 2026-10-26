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
import jakarta.persistence.Version;

/**
 * A person who signs in (owner, employee or both). One row per normalized email, never deleted. Global, not
 * firm-owned: what the person may do in a firm comes only from {@link FirmMember}. The password is stored only as a
 * delegating-encoder hash; the database refuses anything that does not look like one.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected AppUser() {
    }

    /** {@code email} must already be normalized ({@link EmailNormalizer}); {@code passwordHash} comes from {@link PasswordService}. */
    public AppUser(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        if (passwordChangedAt == null) passwordChangedAt = now;
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
        this.passwordChangedAt = Instant.now();
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void disable() { this.status = UserStatus.DISABLED; }

    public void enable() { this.status = UserStatus.ACTIVE; }

    public void markEmailVerified(Instant at) { this.emailVerifiedAt = at; }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public UserStatus getStatus() { return status; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
