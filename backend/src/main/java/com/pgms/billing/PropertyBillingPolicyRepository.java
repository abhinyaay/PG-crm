package com.pgms.billing;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyBillingPolicyRepository extends JpaRepository<PropertyBillingPolicy, Long> {

    List<PropertyBillingPolicy> findByPropertyIdIn(Collection<Long> propertyIds);
}
