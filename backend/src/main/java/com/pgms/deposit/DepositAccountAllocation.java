package com.pgms.deposit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps every allocation of a stay chain to the chain's deposit account. */
@Entity
@Table(name = "deposit_account_allocations")
public class DepositAccountAllocation {

    @Id
    @Column(name = "allocation_id")
    private Long allocationId;

    @Column(name = "deposit_account_id", nullable = false, updatable = false)
    private Long depositAccountId;

    protected DepositAccountAllocation() {
    }

    public DepositAccountAllocation(Long allocationId, Long depositAccountId) {
        this.allocationId = allocationId;
        this.depositAccountId = depositAccountId;
    }

    public Long getAllocationId() {
        return allocationId;
    }

    public Long getDepositAccountId() {
        return depositAccountId;
    }
}
