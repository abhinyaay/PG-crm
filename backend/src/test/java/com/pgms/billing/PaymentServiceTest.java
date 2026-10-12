package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pgms.billing.BillingDtos.IdempotentResult;
import com.pgms.billing.BillingDtos.PaymentResponse;
import com.pgms.billing.BillingDtos.RecordPaymentRequest;
import com.pgms.common.exception.BadRequestException;
import com.pgms.security.AccessGuard;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentServiceTest {

    private PaymentProcessor processor;
    private PaymentService service;
    private final RecordPaymentRequest request = new RecordPaymentRequest(5L, new BigDecimal("1000"), PaymentMethod.UPI,
            "UPI-1", null, null);

    @BeforeEach
    void setUp() {
        processor = mock(PaymentProcessor.class);
        service = new PaymentService(processor, mock(PaymentRepository.class), mock(RefundRepository.class),
                mock(AccessGuard.class));
    }

    @Test
    void rejectsMalformedIdempotencyKeys() {
        assertThatThrownBy(() -> service.recordPayment("short", request)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.recordPayment("has spaces in it", request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(processor);
    }

    @Test
    void sameRequestProducesSameFingerprint() {
        IdempotentResult<PaymentResponse> ok = new IdempotentResult<>(null, false);
        when(processor.recordPayment(eq("key-00000001"), anyString(), eq(request))).thenReturn(ok);
        assertThat(service.recordPayment("key-00000001", request)).isSameAs(ok);
    }

    /**
     * Two concurrent requests with one key but different invoices do not share a row lock; the loser hits the unique
     * index. The service must then answer with a replay of the winner instead of a 500.
     */
    @Test
    void uniqueViolationOnIdempotencyKeyIsResolvedAsReplay() {
        DataIntegrityViolationException duplicate = new DataIntegrityViolationException("dup",
                new ConstraintViolationException("duplicate key", new SQLException("dup", "23505"), "uk_payments_idempotency"));
        when(processor.recordPayment(anyString(), anyString(), any())).thenThrow(duplicate);
        when(processor.organizationOfInvoice(5L)).thenReturn(1L);
        IdempotentResult<PaymentResponse> replay = new IdempotentResult<>(null, true);
        when(processor.findPaymentReplay(eq(1L), eq("key-00000002"), anyString())).thenReturn(Optional.of(replay));

        assertThat(service.recordPayment("key-00000002", request).replayed()).isTrue();
    }

    @Test
    void otherIntegrityViolationsPropagate() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException("dup", "23505"), "uk_payments_external_reference"));
        when(processor.recordPayment(anyString(), anyString(), any())).thenThrow(other);
        assertThatThrownBy(() -> service.recordPayment("key-00000003", request)).isSameAs(other);
    }
}
