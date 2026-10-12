package com.pgms.billing;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A bill for a tenant: monthly RENT (one per allocation per month) or the SECURITY_DEPOSIT at move-in.
 * Totals are always derived from line items inside this aggregate, and every money-moving method enforces the
 * invariants that the database also checks (0 <= amount_paid <= total, discount <= subtotal).
 */
@Entity
@Table(name = "invoices")
public class Invoice extends AuditableEntity {

    @Column(name = "invoice_number", nullable = false, updatable = false)
    private String invoiceNumber;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "allocation_id", nullable = false, updatable = false)
    private Long allocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, updatable = false)
    private InvoiceType invoiceType;

    @Column(name = "billing_month")
    private LocalDate billingMonth;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency;

    private String notes;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    protected Invoice() {
    }

    Invoice(String invoiceNumber, Long organizationId, Long propertyId, Long tenantId, Long allocationId,
            InvoiceType invoiceType, LocalDate billingMonth, LocalDate periodStart, LocalDate periodEnd,
            LocalDate issueDate, LocalDate dueDate, String currency) {
        this.invoiceNumber = invoiceNumber;
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.allocationId = allocationId;
        this.invoiceType = invoiceType;
        this.billingMonth = billingMonth;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
        this.currency = currency;
    }

    public BigDecimal balance() {
        return totalAmount.subtract(amountPaid);
    }

    public boolean isOpen() {
        return status == InvoiceStatus.ISSUED || status == InvoiceStatus.PARTIALLY_PAID || status == InvoiceStatus.OVERDUE;
    }

    public void addCharge(LineItemType type, String description, BigDecimal amount, Instant at, Long userId, LocalDate today) {
        requireNotVoid();
        if (type == LineItemType.DISCOUNT) {
            throw new BusinessRuleException("INVALID_CHARGE_TYPE", "Use applyDiscount for discounts");
        }
        // payments on a deposit invoice are "deposit held" (refunded at move-out): keep deposits and charges apart
        if ((invoiceType == InvoiceType.SECURITY_DEPOSIT) != (type == LineItemType.SECURITY_DEPOSIT)) {
            throw new BusinessRuleException("INVALID_CHARGE_TYPE", invoiceType == InvoiceType.SECURITY_DEPOSIT
                    ? "Extra charges belong on a rent invoice, not on the security deposit"
                    : "Security deposit lines belong on the deposit invoice");
        }
        requirePositive(amount);
        lineItems.add(new InvoiceLineItem(this, type, description, amount, at, userId));
        recalculate(today);
    }

    public void applyDiscount(String description, BigDecimal amount, Instant at, Long userId, LocalDate today) {
        requireNotVoid();
        requirePositive(amount);
        if (amount.compareTo(balance()) > 0) {
            throw new BusinessRuleException("DISCOUNT_EXCEEDS_BALANCE",
                    "Discount " + amount + " exceeds the outstanding balance " + balance());
        }
        lineItems.add(new InvoiceLineItem(this, LineItemType.DISCOUNT, description, amount, at, userId));
        recalculate(today);
    }

    /** Applies a payment; overpayment is rejected rather than silently creating credit. */
    public void applyPayment(BigDecimal amount, LocalDate today) {
        if (!isOpen()) {
            throw new ConflictException("INVOICE_NOT_PAYABLE", "Invoice " + invoiceNumber + " is " + status);
        }
        requirePositive(amount);
        if (amount.compareTo(balance()) > 0) {
            throw new BusinessRuleException("OVERPAYMENT", "Payment " + amount + " exceeds the outstanding balance " + balance());
        }
        amountPaid = amountPaid.add(amount);
        recalculate(today);
    }

    public boolean markOverdueIfDue(LocalDate today) {
        InvoiceStatus before = status;
        recalculate(today);
        return before != status && status == InvoiceStatus.OVERDUE;
    }

    public void voidInvoice(String reason, Instant at) {
        requireNotVoid();
        if (amountPaid.signum() > 0) {
            throw new ConflictException("INVOICE_HAS_PAYMENTS", "Invoices with payments cannot be voided");
        }
        status = InvoiceStatus.VOID;
        voidedAt = at;
        voidReason = reason;
    }

    private void recalculate(LocalDate today) {
        subtotal = lineItems.stream().filter(l -> !l.isDiscount()).map(InvoiceLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        discountTotal = lineItems.stream().filter(InvoiceLineItem::isDiscount).map(InvoiceLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalAmount = subtotal.subtract(discountTotal);
        if (status == InvoiceStatus.VOID) {
            return;
        }
        if (balance().signum() == 0) {
            status = InvoiceStatus.PAID;
        } else if (dueDate.isBefore(today)) {
            status = InvoiceStatus.OVERDUE;
        } else if (amountPaid.signum() > 0) {
            status = InvoiceStatus.PARTIALLY_PAID;
        } else {
            status = InvoiceStatus.ISSUED;
        }
    }

    private void requireNotVoid() {
        if (status == InvoiceStatus.VOID) {
            throw new ConflictException("INVOICE_VOID", "Invoice " + invoiceNumber + " is void");
        }
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Amount must be positive");
        }
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getAllocationId() {
        return allocationId;
    }

    public InvoiceType getInvoiceType() {
        return invoiceType;
    }

    public LocalDate getBillingMonth() {
        return billingMonth;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public String getCurrency() {
        return currency;
    }

    public String getNotes() {
        return notes;
    }

    public List<InvoiceLineItem> getLineItems() {
        return lineItems;
    }
}
