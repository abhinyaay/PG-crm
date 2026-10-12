package com.pgms.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "invoice_line_items")
public class InvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false)
    private LineItemType lineType;

    @Column(nullable = false)
    private String description;

    /** Always positive; DISCOUNT lines are subtracted when totals are computed. */
    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    protected InvoiceLineItem() {
    }

    InvoiceLineItem(Invoice invoice, LineItemType lineType, String description, BigDecimal amount, Instant createdAt,
                    Long createdBy) {
        this.invoice = invoice;
        this.lineType = lineType;
        this.description = description;
        this.amount = amount;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public boolean isDiscount() {
        return lineType == LineItemType.DISCOUNT;
    }

    public Long getId() {
        return id;
    }

    public LineItemType getLineType() {
        return lineType;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
