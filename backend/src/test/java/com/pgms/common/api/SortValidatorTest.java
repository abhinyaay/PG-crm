package com.pgms.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BadRequestException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SortValidatorTest {

    private static final Set<String> ALLOWED = Set.of("dueDate", "createdAt");

    @Test
    void rejectsPropertiesOutsideTheWhitelist() {
        assertThatThrownBy(() -> SortValidator.validate(PageRequest.of(0, 20, Sort.by("passwordHash")), ALLOWED,
                Sort.by("dueDate"))).isInstanceOf(BadRequestException.class);
    }

    /**
     * PostgreSQL returns rows with equal sort keys in no guaranteed order, so LIMIT/OFFSET pages over a non-unique key
     * (many invoices share a due date) can repeat or skip rows. The primary key makes every ordering total.
     */
    @Test
    void appendsTheIdAsTieBreakerSoPagesAreStable() {
        Pageable client = SortValidator.validate(PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "dueDate")), ALLOWED,
                Sort.by("createdAt"));
        assertThat(client.getSort()).containsExactly(Sort.Order.desc("dueDate"), Sort.Order.desc("id"));
        assertThat(client.getPageNumber()).isEqualTo(2);
        assertThat(client.getPageSize()).isEqualTo(20);

        Pageable defaulted = SortValidator.validate(PageRequest.of(0, 10), ALLOWED, Sort.by("createdAt"));
        assertThat(defaulted.getSort()).containsExactly(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    }
}
