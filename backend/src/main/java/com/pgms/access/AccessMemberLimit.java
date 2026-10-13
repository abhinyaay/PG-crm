package com.pgms.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Per-resident daily entry limit. A row with {@code null} means "unlimited"; no row means "property default". */
@Entity
@Table(name = "access_member_limits")
public class AccessMemberLimit {

    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "max_entries_per_day")
    private Integer maxEntriesPerDay;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected AccessMemberLimit() {
    }

    public AccessMemberLimit(Long tenantId) {
        this.tenantId = tenantId;
    }

    public void set(Integer maxEntriesPerDay, Instant at, Long by) {
        this.maxEntriesPerDay = maxEntriesPerDay;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Integer getMaxEntriesPerDay() {
        return maxEntriesPerDay;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
