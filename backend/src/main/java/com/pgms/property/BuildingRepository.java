package com.pgms.property;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    List<Building> findByPropertyIdOrderByCode(Long propertyId);
}
