package com.pgms.moveout;

import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.common.exception.NotFoundException;
import com.pgms.deposit.DepositAccount;
import com.pgms.deposit.DepositAccountRepository;
import com.pgms.moveout.MoveOutCalculator.NoticeTerms;
import com.pgms.moveout.MoveOutDtos.InspectionItemResponse;
import com.pgms.moveout.MoveOutDtos.MoveOutResponse;
import com.pgms.moveout.MoveOutDtos.SettlementView;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Shared pieces of the move-out services: references, notice terms, response mapping. */
@Component
public class MoveOutSupport {

    private final JdbcTemplate jdbc;
    private final AllocationRepository allocationRepository;
    private final TenantRepository tenantRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final MoveOutPolicyService policyService;
    private final Clock clock;

    public MoveOutSupport(JdbcTemplate jdbc, AllocationRepository allocationRepository, TenantRepository tenantRepository,
                          DepositAccountRepository depositAccountRepository, MoveOutPolicyService policyService, Clock clock) {
        this.jdbc = jdbc;
        this.allocationRepository = allocationRepository;
        this.tenantRepository = tenantRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.policyService = policyService;
        this.clock = clock;
    }

    /** MO-202610-000042 from the move_out_seq sequence. */
    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('move_out_seq')", Long.class);
        YearMonth month = YearMonth.now(clock);
        return String.format("MO-%d%02d-%06d", month.getYear(), month.getMonthValue(), value);
    }

    /** Start of the first allocation of the stay chain: a transfer does not restart the lock-in. */
    public LocalDate stayStart(Allocation allocation) {
        Allocation current = allocation;
        while (current.getPreviousAllocationId() != null) {
            Long previousId = current.getPreviousAllocationId();
            current = allocationRepository.findById(previousId).orElseThrow(() -> new NotFoundException("Allocation", previousId));
        }
        return current.getStartDate();
    }

    public NoticeTerms terms(Allocation allocation, LocalDate noticeDate, LocalDate moveOutDate) {
        MoveOutPolicy policy = policyService.effective(allocation.getPropertyId());
        return MoveOutCalculator.noticeTerms(policy.terms(), noticeDate, moveOutDate, stayStart(allocation),
                allocation.getMonthlyRent());
    }

    public MoveOutResponse toResponse(MoveOut m, boolean withItems) {
        String tenantName = tenantRepository.findById(m.getTenantId()).map(Tenant::getFullName).orElse(null);
        SettlementView settlement = null;
        if (m.getSettledAt() != null) {
            DepositAccount account = m.getDepositAccountId() == null ? null
                    : depositAccountRepository.findById(m.getDepositAccountId()).orElse(null);
            settlement = new SettlementView(m.getActualMoveOutDate(), m.getDepositHeld(), m.getOutstandingDues(),
                    m.getDuesAdjusted(), m.getTotalDeductions(), m.getRentCredit(), m.getRefundDue(), m.getPayableByTenant(),
                    m.getRefundDueBy(), m.getSettlementInvoiceId(), m.getDepositAccountId(),
                    account == null ? null : account.getStatus(), account == null ? null : account.getRefundDue(),
                    m.getSettledAt());
        }
        return new MoveOutResponse(m.getId(), m.getReference(), m.getPropertyId(), m.getTenantId(), tenantName,
                m.getAllocationId(), m.getStatus(), m.getInitiatedBy(), m.getNoticeDate(), m.getExpectedMoveOutDate(),
                m.getReason(), m.getReasonText(), m.getRefundUpiVpa(), m.getShortfallDays(), m.getShortfallCharge(),
                m.getLockInPenalty(), m.isChargesWaived(), m.getInspectedAt(), m.getInspectedBy(), m.getInspectionNotes(),
                withItems ? m.getItems().stream().map(InspectionItemResponse::of).toList() : null, settlement,
                m.getWithdrawnAt(), m.getClosedAt(), m.getCloseNote(), m.getCreatedAt());
    }
}
