package com.pgms.common.api;

import com.pgms.common.exception.BadRequestException;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Rejects sort properties that are not explicitly whitelisted. Without this a client could sort on
 * arbitrary (possibly unindexed or sensitive) attributes, or trigger a 500 with an unknown property.
 */
public final class SortValidator {

    private SortValidator() {
    }

    public static Pageable validate(Pageable pageable, Set<String> allowed, Sort defaultSort) {
        if (pageable.getSort().isUnsorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), withTieBreaker(defaultSort));
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new BadRequestException("INVALID_SORT",
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + allowed);
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), withTieBreaker(pageable.getSort()));
    }

    /**
     * Rows with equal sort keys come back in no guaranteed order, so LIMIT/OFFSET pages over a non-unique key (many
     * invoices share a due date) could repeat or skip rows. The primary key as last criterion makes the order total.
     */
    private static Sort withTieBreaker(Sort sort) {
        if (sort.getOrderFor("id") != null) {
            return sort;
        }
        Sort.Direction direction = sort.stream().reduce((first, second) -> second).map(Sort.Order::getDirection)
                .orElse(Sort.Direction.ASC);
        return sort.and(Sort.by(direction, "id"));
    }
}
