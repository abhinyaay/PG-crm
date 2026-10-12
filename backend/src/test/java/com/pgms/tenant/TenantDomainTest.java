package com.pgms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BusinessRuleException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TenantDomainTest {

    @Test
    void lifecycleKeepsFirstJoiningDateAcrossReturns() {
        Tenant tenant = new Tenant(1L, 1L, "Asha", "ASHA@Example.com", "+919900000001");
        assertThat(tenant.getEmail()).isEqualTo("asha@example.com");
        tenant.checkIn(1L, LocalDate.of(2025, 1, 10));
        tenant.giveNotice();
        tenant.moveOut(LocalDate.of(2025, 6, 1));
        tenant.checkIn(2L, LocalDate.of(2026, 2, 1));
        assertThat(tenant.getJoiningDate()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(tenant.getPropertyId()).isEqualTo(2L);
        assertThat(tenant.getMoveOutDate()).isNull();
    }

    @Test
    void residentCannotBeCheckedInTwiceOrDeleted() {
        Tenant tenant = new Tenant(1L, 1L, "Asha", null, "+919900000001");
        tenant.checkIn(1L, LocalDate.now());
        assertThatThrownBy(() -> tenant.checkIn(1L, LocalDate.now())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> tenant.softDelete(Instant.now())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void documentNumbersAreMaskedBeforeStorage() {
        assertThat(TenantDocument.mask("1234 5678 9012")).isEqualTo("********9012");
        assertThat(TenantDocument.mask("AB12")).isEqualTo("****");
        TenantDocument doc = new TenantDocument(1L, DocumentType.PASSPORT, "Z1234567", null);
        assertThat(doc.getDocumentNumberMasked()).doesNotContain("Z123").endsWith("4567");
    }
}
