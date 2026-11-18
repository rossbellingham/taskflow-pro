package com.taskflow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Security state for one user (see V6 migration). Created the first time a password is reset or changed;
 * a user without a row has no flag set and no sessions revoked.
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** Login sessions that started before this instant are no longer accepted. */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "sessions_valid_after", nullable = false)
    private Instant sessionsValidAfter = now();

    protected UserSecurity() {
    }

    public UserSecurity(Long userId) {
        this.userId = userId;
    }

    /** An admin set a temporary password: force a change at next use and end every existing session. */
    public void passwordResetByAdmin() {
        this.mustChangePassword = true;
        this.sessionsValidAfter = now();
    }

    /** The user chose a new password: clear the flag and end every session started before now. */
    public void passwordChangedByUser() {
        this.mustChangePassword = false;
        this.sessionsValidAfter = now();
    }

    // Truncated to what PostgreSQL stores, so the value read back equals the value written
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public Instant getSessionsValidAfter() {
        return sessionsValidAfter;
    }
}
