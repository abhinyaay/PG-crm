package com.pgms.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable late-fee ledger row pointing at the invoice line it created: a CHARGE (LATE_FEE line, idempotent per
 * {@code (invoice, periodKey)}) or a WAIVER (DISCOUNT line).
 */
@Entity
@Table(name = "late_fee_entries")
public class LateFeeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "line_item_id", nullable = false, updatable = false)
    private Long lineItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LateFeeEntryKind kind;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private LateFeeType rule;

    @Column(name = "period_key", updatable = false)
    private LocalDate periodKey;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected LateFeeEntry() {
    }

    private LateFeeEntry(Invoice invoice, Long lineItemId, LateFeeEntryKind kind, LateFeeType rule, LocalDate periodKey,
                         BigDecimal amount, String reason, Instant createdAt, Long createdBy) {
        this.organizationId = invoice.getOrganizationId();
        this.invoiceId = invoice.getId();
        this.lineItemId = lineItemId;
        this.kind = kind;
        this.rule = rule;
        this.periodKey = periodKey;
        this.amount = amount;
        this.reason = reason;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    static LateFeeEntry charge(Invoice invoice, Long lineItemId, LateFeeType rule, LocalDate periodKey, BigDecimal amount,
                               Instant at) {
        return new LateFeeEntry(invoice, lineItemId, LateFeeEntryKind.CHARGE, rule, periodKey, amount, null, at, null);
    }

    static LateFeeEntry waiver(Invoice invoice, Long lineItemId, BigDecimal amount, String reason, Instant at, Long userId) {
        return new LateFeeEntry(invoice, lineItemId, LateFeeEntryKind.WAIVER, null, null, amount, reason, at, userId);
    }

    public Long getId() {
        return id;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public Long getLineItemId() {
        return lineItemId;
    }

    public LateFeeEntryKind getKind() {
        return kind;
    }

    public LateFeeType getRule() {
        return rule;
    }

    public LocalDate getPeriodKey() {
        return periodKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
