package com.pgms.common.exception;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Maps database constraint names to client-meaningful errors. The database is the last line of defence for
 * invariants such as "one active allocation per bed"; when it fires we still want a precise 409, not a 500.
 */
public final class ConstraintViolationTranslator {

    private static final Map<String, String[]> KNOWN = Map.ofEntries(
            Map.entry("uk_users_email", new String[] {"EMAIL_ALREADY_EXISTS", "A user with this email already exists"}),
            Map.entry("uk_organizations_slug", new String[] {"ORGANIZATION_EXISTS", "Organization slug already in use"}),
            Map.entry("uk_properties_org_code", new String[] {"PROPERTY_CODE_EXISTS", "Property code already exists"}),
            Map.entry("uk_property_assignments", new String[] {"ALREADY_ASSIGNED", "User is already assigned to this property"}),
            Map.entry("uk_buildings_property_code", new String[] {"BUILDING_EXISTS", "Building code already exists in property"}),
            Map.entry("uk_floors_building_number", new String[] {"FLOOR_EXISTS", "Floor already exists in building"}),
            Map.entry("uk_rooms_property_number", new String[] {"ROOM_EXISTS", "Room number already exists in property"}),
            Map.entry("uk_beds_room_code", new String[] {"BED_EXISTS", "Bed code already exists in room"}),
            Map.entry("uk_tenants_org_phone", new String[] {"TENANT_PHONE_EXISTS", "A tenant with this phone already exists"}),
            Map.entry("uk_tenants_org_email", new String[] {"TENANT_EMAIL_EXISTS", "A tenant with this email already exists"}),
            Map.entry("uk_tenant_documents_type", new String[] {"DOCUMENT_EXISTS", "Document of this type already recorded"}),
            Map.entry("uk_bookings_reserved_bed", new String[] {"BED_ALREADY_RESERVED", "Bed already has an active reservation"}),
            Map.entry("uk_bookings_reserved_tenant",
                    new String[] {"TENANT_ALREADY_RESERVED", "Tenant already has an active reservation"}),
            Map.entry("uk_allocations_active_bed", new String[] {"BED_ALREADY_ALLOCATED", "Bed is already allocated"}),
            Map.entry("uk_allocations_active_tenant",
                    new String[] {"TENANT_ALREADY_ALLOCATED", "Tenant already has an active allocation"}),
            Map.entry("ex_allocations_bed_overlap",
                    new String[] {"BED_ALLOCATION_OVERLAP", "Bed is already allocated for an overlapping period"}),
            Map.entry("ex_allocations_tenant_overlap",
                    new String[] {"TENANT_ALLOCATION_OVERLAP", "Tenant already has an allocation for an overlapping period"}),
            Map.entry("uk_invoices_rent_month", new String[] {"INVOICE_EXISTS", "Rent invoice already exists for this month"}),
            Map.entry("uk_invoices_deposit", new String[] {"INVOICE_EXISTS", "Deposit invoice already exists"}),
            Map.entry("uk_payments_idempotency", new String[] {"DUPLICATE_REQUEST", "Duplicate payment request"}),
            Map.entry("uk_payments_external_reference",
                    new String[] {"DUPLICATE_TRANSACTION", "This transaction reference was already recorded"}),
            Map.entry("uk_refunds_idempotency", new String[] {"DUPLICATE_REQUEST", "Duplicate refund request"}),
            Map.entry("uk_notification_preferences",
                    new String[] {"CONCURRENT_MODIFICATION", "Preferences were changed concurrently. Please retry."}),
            Map.entry("organization_notification_settings_pkey",
                    new String[] {"CONCURRENT_MODIFICATION", "Settings were changed concurrently. Please retry."}),
            // collections (V5)
            Map.entry("property_billing_policies_pkey",
                    new String[] {"CONCURRENT_MODIFICATION", "The billing policy was created concurrently. Please retry."}),
            Map.entry("uk_late_fee_invoice_period", new String[] {"LATE_FEE_EXISTS", "Late fee already charged for this period"}),
            Map.entry("uk_payment_claims_open_invoice",
                    new String[] {"CLAIM_ALREADY_PENDING", "A payment claim for this invoice is already waiting for verification"}),
            Map.entry("uk_payment_claims_utr",
                    new String[] {"DUPLICATE_TRANSACTION", "This transaction reference was already submitted"}),
            Map.entry("uk_payment_mandates_live_tenant",
                    new String[] {"MANDATE_EXISTS", "An AutoPay mandate is already set up for this tenant"}),
            Map.entry("uk_autopay_attempts_invoice_day",
                    new String[] {"ATTEMPT_EXISTS", "An AutoPay debit was already attempted for this invoice today"}),
            Map.entry("uk_autopay_attempts_invoice_no",
                    new String[] {"ATTEMPT_EXISTS", "An AutoPay debit was already attempted for this invoice"}),
            Map.entry("uk_payment_webhook_events", new String[] {"DUPLICATE_EVENT", "Webhook event already processed"}));

    private static final Pattern CONSTRAINT_IN_MESSAGE = Pattern.compile("constraint \"([A-Za-z0-9_]+)\"");

    private ConstraintViolationTranslator() {
    }

    public static Optional<String> constraintName(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return Optional.of(cve.getConstraintName().toLowerCase(Locale.ROOT));
            }
            // Hibernate's PostgreSQL extractor does not name EXCLUDE constraint violations (SQLSTATE 23P01),
            // so fall back to the driver message: ... violates exclusion constraint "ex_allocations_bed_overlap"
            if (cause.getMessage() != null) {
                Matcher matcher = CONSTRAINT_IN_MESSAGE.matcher(cause.getMessage());
                if (matcher.find()) {
                    return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
                }
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }

    public static ApiException translate(DataIntegrityViolationException ex) {
        String constraint = constraintName(ex).orElse("");
        String[] known = KNOWN.get(constraint);
        if (known != null) {
            return new ConflictException(known[0], known[1]);
        }
        return new ConflictException("DATA_INTEGRITY_VIOLATION", "The request conflicts with existing data");
    }

    public static boolean isConstraint(DataIntegrityViolationException ex, String name) {
        return constraintName(ex).map(name::equals).orElse(false);
    }
}
