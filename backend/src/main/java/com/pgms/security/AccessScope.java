package com.pgms.security;

import com.pgms.property.Property;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/**
 * The slice of data a caller may see. {@code organizationId == null} means all organizations (SUPER_ADMIN);
 * {@code propertyIds == null} means every property inside the organization (PROPERTY_ADMIN).
 * MANAGER/STAFF get an explicit, possibly empty, property set.
 */
public record AccessScope(Long organizationId, Set<Long> propertyIds) {

    public static AccessScope global() {
        return new AccessScope(null, null);
    }

    public boolean restrictedToProperties() {
        return propertyIds != null;
    }

    /** True when the caller can see nothing at all (e.g. a manager with no assignments) - skip the query. */
    public boolean isEmpty() {
        return propertyIds != null && propertyIds.isEmpty();
    }

    /** Converts the scope into a JPA predicate over entities exposing {@code organizationId} and {@code propertyId}. */
    public <T> Specification<T> toSpecification() {
        return toSpecification("organizationId", "propertyId");
    }

    /**
     * Same as {@link #toSpecification()} for entities that only carry a {@code propertyId} (rooms, beds):
     * organization scoping is expressed as a sub-select on properties instead of loading ids into memory.
     */
    public <T> Specification<T> toPropertySpecification() {
        return (root, query, cb) -> {
            if (propertyIds != null) {
                return propertyIds.isEmpty() ? cb.disjunction() : root.get("propertyId").in(propertyIds);
            }
            if (organizationId == null) {
                return cb.conjunction();
            }
            Subquery<Long> orgProperties = query.subquery(Long.class);
            Root<Property> property = orgProperties.from(Property.class);
            orgProperties.select(property.get("id")).where(cb.equal(property.get("organizationId"), organizationId));
            return root.get("propertyId").in(orgProperties);
        };
    }

    public <T> Specification<T> toSpecification(String organizationAttribute, String propertyAttribute) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (organizationId != null) {
                predicate = cb.and(predicate, cb.equal(root.get(organizationAttribute), organizationId));
            }
            if (propertyIds != null) {
                predicate = propertyIds.isEmpty()
                        ? cb.disjunction()
                        : cb.and(predicate, root.get(propertyAttribute).in(propertyIds));
            }
            return predicate;
        };
    }
}
