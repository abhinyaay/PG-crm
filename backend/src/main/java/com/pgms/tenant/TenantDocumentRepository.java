package com.pgms.tenant;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDocumentRepository extends JpaRepository<TenantDocument, Long> {

    List<TenantDocument> findByTenantIdOrderByCreatedAt(Long tenantId);
}
