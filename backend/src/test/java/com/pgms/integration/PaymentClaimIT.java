package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.billing.CollectionsJobs;
import com.pgms.integration.Api.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec 2: UPI pay link and the tenant "I've paid" claim → manager verification workflow. */
class PaymentClaimIT extends AbstractCollectionsIT {

    private static final AtomicLong UTR = new AtomicLong(400_000_000_000L + System.nanoTime() % 100_000_000_000L);

    @Autowired
    private CollectionsJobs collectionsJobs;

    private static String utr() {
        return String.valueOf(UTR.incrementAndGet());
    }

    private Response claim(String token, long invoiceId, String amount, String method, String reference, String note) {
        return api.post("/api/v1/invoices/" + invoiceId + "/payment-claims", token, "{\"amount\":" + amount + ",\"method\":\""
                + method + "\",\"utr\":" + (reference == null ? "null" : "\"" + reference + "\"") + ",\"paidOn\":\"" + today
                + "\",\"note\":" + (note == null ? "null" : "\"" + note + "\"") + "}");
    }

    @Test
    void payLinkNeedsAConfiguredVpaAndEncodesTheUpiUri() {
        Response missing = api.get("/api/v1/invoices/" + rentInvoiceId + "/pay-link", tenantToken);
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("code").asText()).isEqualTo("UPI_NOT_CONFIGURED");

        setPolicy(layout.propertyId(), "{\"enabled\":false,\"type\":\"FLAT\",\"value\":0,\"graceDays\":0}", "[1]",
                "{\"vpa\":\"green.nest@okhdfc\",\"payeeName\":\"Green Nest & Co\"}");
        Response link = api.get("/api/v1/invoices/" + rentInvoiceId + "/pay-link", tenantToken);
        assertThat(link.status()).isEqualTo(200);
        String invoiceNumber = link.body().get("invoiceNumber").asText();
        String balance = balance(rentInvoiceId).setScale(2).toPlainString();
        assertThat(link.body().get("upiUri").asText()).isEqualTo("upi://pay?pa=green.nest@okhdfc&pn=Green%20Nest%20%26%20Co&am="
                + balance + "&cu=INR&tn=Rent%20" + invoiceNumber + "&tr=" + invoiceNumber);
        assertThat(link.body().get("qrPayload").asText()).isEqualTo(link.body().get("upiUri").asText());

        Response part = api.get("/api/v1/invoices/" + rentInvoiceId + "/pay-link?amount=100", managerToken);
        assertThat(part.body().get("amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(api.get("/api/v1/invoices/" + rentInvoiceId + "/pay-link?amount=999999", tenantToken).body().get("code")
                .asText()).isEqualTo("OVERPAYMENT");
        assertThat(api.get("/api/v1/invoices/" + rentInvoiceId + "/pay-link", otherManagerToken).status()).isEqualTo(403);
    }

    @Test
    void submittedClaimIsApprovedIntoARealPaymentWithTheUtr() {
        String reference = utr();
        String balance = balance(rentInvoiceId).toPlainString();
        Response submitted = claim(tenantToken, rentInvoiceId, balance, "UPI", reference, "Paid from GPay");
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(201);
        assertThat(submitted.body().get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.body().get("claimReference").asText()).startsWith("PCL-");
        long claimId = submitted.id();
        assertThat(notifications("PAYMENT_CLAIM_SUBMITTED", "PAYMENT_CLAIM", claimId, "IN_APP")).isEqualTo(1);

        Response queue = api.get("/api/v1/payment-claims?status=SUBMITTED&propertyId=" + layout.propertyId(), managerToken);
        assertThat(queue.body().get("content")).hasSize(1);
        assertThat(queue.body().at("/content/0/tenantName").asText()).startsWith("Tenant ");
        assertThat(queue.body().at("/content/0/invoiceNumber").asText()).startsWith("INV-");
        assertThat(api.get("/api/v1/payment-claims?propertyId=" + layout.propertyId(), otherManagerToken).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/payment-claims", otherManagerToken).body().get("content")).isEmpty();
        assertThat(api.get("/api/v1/payment-claims", tenantToken).body().get("content")).hasSize(1);

        assertThat(api.post("/api/v1/payment-claims/" + claimId + "/approve", staffToken, "{}").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/payment-claims/" + claimId + "/approve", otherManagerToken, "{}").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/payment-claims/" + claimId + "/approve", foreignAdminToken, "{}").status()).isEqualTo(404);
        assertThat(api.post("/api/v1/payment-claims/" + claimId + "/approve", tenantToken, "{}").status()).isEqualTo(403);

        Response approved = api.post("/api/v1/payment-claims/" + claimId + "/approve", managerToken, "{}");
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body().at("/claim/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.body().at("/payment/externalReference").asText()).isEqualTo(reference);
        assertThat(approved.body().at("/payment/method").asText()).isEqualTo("UPI");
        assertThat(status(rentInvoiceId)).isEqualTo("PAID");
        assertThat(fixtures.count("SELECT count(*) FROM payments WHERE invoice_id = ? AND idempotency_key = ?", rentInvoiceId,
                "claim:" + claimId)).isEqualTo(1);
        assertThat(notifications("PAYMENT_RECEIVED", "PAYMENT", approved.body().at("/payment/id").asLong(), "IN_APP"))
                .isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'PAYMENT_CLAIM_APPROVED' AND entity_id = ?",
                claimId)).isEqualTo(1);

        Response again = api.post("/api/v1/payment-claims/" + claimId + "/approve", managerToken, "{}");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asText()).isEqualTo("CLAIM_NOT_PENDING");
    }

    @Test
    void concurrentApprovalsRecordExactlyOnePayment() throws Exception {
        long claimId = claim(tenantToken, rentInvoiceId, "1000", "UPI", utr(), null).id();
        List<Callable<Response>> approvals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String token = i % 2 == 0 ? managerToken : adminToken;
            approvals.add(() -> api.post("/api/v1/payment-claims/" + claimId + "/approve", token, "{}"));
        }

        List<Response> results = ConcurrencySupport.runConcurrently(approvals);

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .hasSize(3).allSatisfy(r -> assertThat(r.body().get("code").asText()).isEqualTo("CLAIM_NOT_PENDING"));
        assertThat(fixtures.count("SELECT count(*) FROM payments WHERE invoice_id = ?", rentInvoiceId)).isEqualTo(1);
        assertThat(fixtures.decimal("SELECT amount_paid FROM invoices WHERE id = ?", rentInvoiceId)).isEqualByComparingTo("1000");
    }

    @Test
    void reducedApprovalKeepsTheClaimedAmount() {
        long claimId = claim(tenantToken, rentInvoiceId, "1500", "BANK_TRANSFER", "hdfc12345678", null).id();
        Response approved = api.post("/api/v1/payment-claims/" + claimId + "/approve", managerToken,
                "{\"amount\":1200,\"notes\":\"Only 1200 credited\"}");
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().at("/claim/amount").decimalValue()).isEqualByComparingTo("1500");
        assertThat(approved.body().at("/claim/approvedAmount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(approved.body().at("/claim/utr").asText()).isEqualTo("HDFC12345678");
        assertThat(approved.body().at("/payment/amount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(approved.body().at("/payment/notes").asText()).contains("claimed 1500").contains("Only 1200 credited");

        long second = claim(tenantToken, rentInvoiceId, "100", "CASH", null, "Paid to warden Ravi").id();
        Response more = api.post("/api/v1/payment-claims/" + second + "/approve", managerToken, "{\"amount\":200}");
        assertThat(more.status()).isEqualTo(422);
        assertThat(more.body().get("code").asText()).isEqualTo("APPROVAL_EXCEEDS_CLAIM");
    }

    @Test
    void duplicatesAndInvalidClaimsAreRejected() {
        String reference = utr();
        assertThat(claim(tenantToken, rentInvoiceId, "100", "UPI", reference, null).status()).isEqualTo(201);

        Response pending = claim(tenantToken, rentInvoiceId, "100", "UPI", utr(), null);
        assertThat(pending.status()).isEqualTo(409);
        assertThat(pending.body().get("code").asText()).isEqualTo("CLAIM_ALREADY_PENDING");

        Response sameUtr = claim(tenantToken, depositInvoiceId, "100", "UPI", reference, null);
        assertThat(sameUtr.status()).isEqualTo(409);
        assertThat(sameUtr.body().get("code").asText()).isEqualTo("DUPLICATE_TRANSACTION");

        // a reference the office already recorded by hand cannot be claimed again
        String recorded = utr();
        assertThat(api.post("/api/v1/payments", adminToken, "{\"invoiceId\":" + depositInvoiceId + ",\"amount\":100,"
                + "\"method\":\"UPI\",\"externalReference\":\"" + recorded + "\"}", "Idempotency-Key", "it-" + recorded)
                .status()).isEqualTo(201);
        assertThat(claim(tenantToken, depositInvoiceId, "100", "UPI", recorded, null).body().get("code").asText())
                .isEqualTo("DUPLICATE_TRANSACTION");

        assertThat(claim(tenantToken, depositInvoiceId, "100", "UPI", "12345", null).status()).isEqualTo(400);
        assertThat(claim(tenantToken, depositInvoiceId, "100", "CASH", null, null).body().get("code").asText())
                .isEqualTo("NOTE_REQUIRED");
        assertThat(claim(tenantToken, depositInvoiceId, "100", "CASH", "123456789012", "x").status()).isEqualTo(400);
        assertThat(claim(tenantToken, depositInvoiceId, "100", "CARD", "123456789012", null).status()).isEqualTo(400);
        assertThat(claim(tenantToken, depositInvoiceId, "999999", "UPI", utr(), null).body().get("code").asText())
                .isEqualTo("OVERPAYMENT");
        assertThat(api.post("/api/v1/invoices/" + depositInvoiceId + "/payment-claims", tenantToken, "{\"amount\":10,"
                + "\"method\":\"UPI\",\"utr\":\"" + utr() + "\",\"paidOn\":\"" + today.plusDays(1) + "\"}").status())
                .isEqualTo(400);
        assertThat(claim(managerToken, depositInvoiceId, "100", "UPI", utr(), null).status()).isEqualTo(403);

        fixtures.jdbc().update("UPDATE invoices SET status = 'VOID' WHERE id = ?", depositInvoiceId);
        assertThat(claim(tenantToken, depositInvoiceId, "100", "UPI", utr(), null).body().get("code").asText())
                .isEqualTo("INVOICE_NOT_PAYABLE");
    }

    @Test
    void tenantsCannotSeeOrClaimSomeoneElsesInvoice() {
        long other = fixtures.tenant(org, layout.propertyId());
        long otherUser = fixtures.user(org, 5, "resident2");
        fixtures.linkTenantUser(other, otherUser);
        String otherToken = api.login(org.email("resident2"));
        long claimId = claim(tenantToken, rentInvoiceId, "100", "UPI", utr(), null).id();

        assertThat(claim(otherToken, rentInvoiceId, "100", "UPI", utr(), null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/payment-claims/" + claimId, otherToken).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/payment-claims/" + claimId + "/cancel", otherToken, null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/payment-claims", otherToken).body().get("content")).isEmpty();
        assertThat(api.get("/api/v1/invoices/" + rentInvoiceId + "/pay-link", otherToken).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/payment-claims/" + claimId, otherManagerToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/payment-claims/" + claimId, managerToken).status()).isEqualTo(200);
    }

    @Test
    void rejectionNotifiesTheTenantAndCancelledClaimsFreeTheInvoice() {
        long rejectedId = claim(tenantToken, rentInvoiceId, "100", "UPI", utr(), null).id();
        assertThat(api.post("/api/v1/payment-claims/" + rejectedId + "/reject", managerToken, "{\"reason\":\" \"}").status())
                .isEqualTo(400);
        Response rejected = api.post("/api/v1/payment-claims/" + rejectedId + "/reject", managerToken,
                "{\"reason\":\"No credit found for this UTR\"}");
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.body().get("rejectionReason").asText()).isEqualTo("No credit found for this UTR");
        assertThat(notifications("PAYMENT_CLAIM_REJECTED", "PAYMENT_CLAIM", rejectedId, "IN_APP")).isEqualTo(1);
        assertThat(notifications("PAYMENT_CLAIM_REJECTED", "PAYMENT_CLAIM", rejectedId, "SMS")).isEqualTo(1);
        assertThat(api.post("/api/v1/payment-claims/" + rejectedId + "/cancel", tenantToken, null).status()).isEqualTo(409);

        long cancelledId = claim(tenantToken, rentInvoiceId, "100", "UPI", utr(), null).id();
        Response cancelled = api.post("/api/v1/payment-claims/" + cancelledId + "/cancel", tenantToken, null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(api.post("/api/v1/payment-claims/" + cancelledId + "/approve", managerToken, "{}").status()).isEqualTo(409);
        assertThat(claim(tenantToken, rentInvoiceId, "100", "UPI", utr(), null).status()).isEqualTo(201);
    }

    @Test
    void claimsWaitingLongerThanTheSlaAreDigestedOncePerDay() {
        long claimId = claim(tenantToken, rentInvoiceId, "100", "UPI", utr(), null).id();
        collectionsJobs.runPendingClaimsDigest(today);
        assertThat(notifications("PAYMENT_CLAIMS_PENDING", "PROPERTY", layout.propertyId(), "IN_APP")).isZero();

        fixtures.jdbc().update("UPDATE payment_claims SET created_at = now() - interval '25 hours' WHERE id = ?", claimId);
        collectionsJobs.runPendingClaimsDigest(today);
        collectionsJobs.runPendingClaimsDigest(today);

        // the property's manager and the organization's admin, once each
        assertThat(notifications("PAYMENT_CLAIMS_PENDING", "PROPERTY", layout.propertyId(), "IN_APP")).isEqualTo(2);
        assertThat(notifications("PAYMENT_CLAIMS_PENDING", "PROPERTY", layout.propertyId(), "EMAIL")).isEqualTo(2);
        assertThat(notifications("PAYMENT_CLAIMS_PENDING", "PROPERTY", layout.propertyId(), "SMS")).isZero();
    }
}
