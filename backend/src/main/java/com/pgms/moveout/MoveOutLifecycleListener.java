package com.pgms.moveout;

import com.pgms.billing.InvoiceRepository;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.booking.BookingEvents;
import com.pgms.deposit.DepositEvents;
import com.pgms.moveout.MoveOutCalculator.NoticeTerms;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEnums.Reason;
import com.pgms.moveout.MoveOutEnums.Status;
import com.pgms.security.CurrentUserProvider;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps move-out cases consistent with changes made elsewhere, inside the same transaction:
 * notices given / withdrawn through the allocation endpoints, transfers while on notice, and the refund that
 * completes a settled case.
 */
@Component
public class MoveOutLifecycleListener {

    private final MoveOutRepository repository;
    private final MoveOutSupport support;
    private final AllocationRepository allocationRepository;
    private final InvoiceRepository invoiceRepository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public MoveOutLifecycleListener(MoveOutRepository repository, MoveOutSupport support,
                                    AllocationRepository allocationRepository, InvoiceRepository invoiceRepository,
                                    CurrentUserProvider currentUserProvider, Clock clock) {
        this.repository = repository;
        this.support = support;
        this.allocationRepository = allocationRepository;
        this.invoiceRepository = invoiceRepository;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    /** Notice through {@code POST /allocations/{id}/notice}: open (or reschedule) the resident's case. */
    @EventListener
    public void on(BookingEvents.NoticeGiven e) {
        Allocation allocation = allocationRepository.findById(e.allocationId()).orElseThrow();
        NoticeTerms terms = support.terms(allocation, e.noticeDate(), e.expectedEndDate());
        repository.findOpenForTenantForUpdate(e.tenantId()).ifPresentOrElse(
                open -> open.reschedule(e.expectedEndDate(), terms, open.isChargesWaived()),
                () -> {
                    MoveOut moveOut = new MoveOut(support.nextReference(), e.organizationId(), e.propertyId(), e.tenantId(),
                            e.allocationId(), currentUserProvider.currentUserId().orElse(null), Initiator.STAFF, e.noticeDate(),
                            e.expectedEndDate(), Reason.OTHER, "Notice recorded on the allocation", null);
                    moveOut.applyTerms(terms);
                    repository.save(moveOut);
                });
    }

    @EventListener
    public void on(BookingEvents.NoticeWithdrawn e) {
        repository.findOpenForTenantForUpdate(e.tenantId()).filter(m -> m.getStatus() == Status.NOTICE_GIVEN)
                .ifPresent(m -> m.withdraw(clock.instant()));
    }

    /** An open case follows the resident to the new bed. */
    @EventListener
    public void on(BookingEvents.TenantTransferred e) {
        repository.findOpenForTenantForUpdate(e.tenantId()).ifPresent(m -> allocationRepository.findById(e.toAllocationId())
                .ifPresent(next -> m.followAllocation(next.getId(), next.getPropertyId())));
    }

    /** Refund fully paid and nothing owed by the resident: the case is done. */
    @EventListener
    public void on(DepositEvents.DepositRefunded e) {
        if (e.refundDueAfter().signum() > 0) {
            return;
        }
        repository.findSettledForDepositForUpdate(e.depositAccountId()).ifPresent(m -> {
            if (invoiceRepository.outstandingForTenant(m.getTenantId()).signum() == 0) {
                m.close("Deposit refund paid", clock.instant());
            }
        });
    }
}
