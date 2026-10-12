package com.pgms.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * KYC document metadata. The full ID number is never persisted - only a masked form - which limits the blast
 * radius of a data leak. {@code storageKey} points at the scanned file in object storage (upload not in scope).
 */
@Entity
@Table(name = "tenant_documents")
public class TenantDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Column(name = "document_number_masked", nullable = false)
    private String documentNumberMasked;

    @Column(name = "storage_key")
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantDocument() {
    }

    public TenantDocument(Long tenantId, DocumentType documentType, String rawNumber, String storageKey) {
        this.tenantId = tenantId;
        this.documentType = documentType;
        this.documentNumberMasked = mask(rawNumber);
        this.storageKey = storageKey;
    }

    static String mask(String raw) {
        String compact = raw.replaceAll("\\s", "");
        if (compact.length() <= 4) {
            return "****";
        }
        return "*".repeat(Math.min(compact.length() - 4, 12)) + compact.substring(compact.length() - 4);
    }

    public void review(VerificationStatus status, Long reviewerId, Instant at) {
        this.verificationStatus = status;
        this.verifiedBy = reviewerId;
        this.verifiedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getDocumentNumberMasked() {
        return documentNumberMasked;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
