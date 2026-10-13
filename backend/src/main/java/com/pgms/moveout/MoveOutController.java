package com.pgms.moveout;

import com.pgms.common.api.PageResponse;
import com.pgms.moveout.MoveOutDtos.ChangeDateRequest;
import com.pgms.moveout.MoveOutDtos.CloseRequest;
import com.pgms.moveout.MoveOutDtos.CompleteRequest;
import com.pgms.moveout.MoveOutDtos.CreateMoveOutRequest;
import com.pgms.moveout.MoveOutDtos.InspectionRequest;
import com.pgms.moveout.MoveOutDtos.MoveOutResponse;
import com.pgms.moveout.MoveOutDtos.PolicyResponse;
import com.pgms.moveout.MoveOutDtos.PreviewResponse;
import com.pgms.moveout.MoveOutDtos.RefundRequest;
import com.pgms.moveout.MoveOutDtos.SettlementPreview;
import com.pgms.moveout.MoveOutDtos.UpdatePolicyRequest;
import com.pgms.moveout.MoveOutEnums.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Move-outs (notice, inspection, settlement)")
public class MoveOutController {

    private static final String ADMINS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')";
    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";
    private static final String OPERATORS_AND_TENANT = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')";
    private static final String ALL_STAFF_AND_TENANT = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF','TENANT')";

    private final MoveOutService moveOutService;
    private final MoveOutPolicyService policyService;

    public MoveOutController(MoveOutService moveOutService, MoveOutPolicyService policyService) {
        this.moveOutService = moveOutService;
        this.policyService = policyService;
    }

    @GetMapping("/properties/{propertyId}/moveout-policy")
    @PreAuthorize(ALL_STAFF_AND_TENANT)
    @Operation(summary = "Notice period, lock-in, final-month billing, refund SLA and inspection checklist of a property")
    public PolicyResponse policy(@PathVariable Long propertyId) {
        return policyService.get(propertyId);
    }

    @PutMapping("/properties/{propertyId}/moveout-policy")
    @PreAuthorize(ADMINS)
    public PolicyResponse updatePolicy(@PathVariable Long propertyId, @Valid @RequestBody UpdatePolicyRequest request) {
        return policyService.update(propertyId, request);
    }

    @GetMapping("/move-outs/preview")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @Operation(summary = "What leaving on moveOutDate costs: earliest no-penalty date, shortfall, lock-in, estimated refund."
            + " Residents omit allocationId (their active stay is used)")
    public PreviewResponse preview(@RequestParam(required = false) Long allocationId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate moveOutDate) {
        return moveOutService.preview(allocationId, moveOutDate);
    }

    @PostMapping("/move-outs")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @Operation(summary = "Give notice (resident: own active stay; staff: allocationId)")
    public ResponseEntity<MoveOutResponse> create(@Valid @RequestBody CreateMoveOutRequest request) {
        MoveOutResponse response = moveOutService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/move-outs/" + response.id())).body(response);
    }

    @GetMapping("/move-outs")
    @PreAuthorize(ALL_STAFF_AND_TENANT)
    @Operation(summary = "Move-out board; sort: expectedMoveOutDate (default), noticeDate, createdAt")
    public PageResponse<MoveOutResponse> search(@RequestParam(required = false) Long propertyId,
                                                @RequestParam(required = false) Status status,
                                                @RequestParam(required = false) Long tenantId,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                LocalDate to,
                                                Pageable pageable) {
        return moveOutService.search(propertyId, status, tenantId, from, to, pageable);
    }

    @GetMapping("/move-outs/{id}")
    @PreAuthorize(ALL_STAFF_AND_TENANT)
    public MoveOutResponse get(@PathVariable Long id) {
        return moveOutService.get(id);
    }

    @PutMapping("/move-outs/{id}/date")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Change the expected move-out date (recomputes charges); waiveCharges waives shortfall and lock-in")
    public MoveOutResponse changeDate(@PathVariable Long id, @Valid @RequestBody ChangeDateRequest request) {
        return moveOutService.changeDate(id, request);
    }

    @PostMapping("/move-outs/{id}/withdraw")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @Operation(summary = "Withdraw the notice (only before the inspection)")
    public MoveOutResponse withdraw(@PathVariable Long id) {
        return moveOutService.withdraw(id);
    }

    @PutMapping("/move-outs/{id}/inspection")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')")
    @Operation(summary = "Record (or replace) the inspection checklist; status becomes INSPECTED")
    public MoveOutResponse inspect(@PathVariable Long id, @Valid @RequestBody InspectionRequest request) {
        return moveOutService.inspect(id, request);
    }

    @GetMapping("/move-outs/{id}/settlement-preview")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Settlement wizard preview: open dues, deductions and how the deposit covers them (no changes)")
    public SettlementPreview settlementPreview(@PathVariable Long id,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                               LocalDate actualMoveOutDate,
                                               @RequestParam(required = false) Boolean waiveCharges) {
        return moveOutService.settlementPreview(id, actualMoveOutDate, waiveCharges);
    }

    @PostMapping("/move-outs/{id}/complete")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Check out and persist the settlement: deductions invoiced, deposit applied to dues, refund due recorded")
    public MoveOutResponse complete(@PathVariable Long id, @Valid @RequestBody CompleteRequest request) {
        return moveOutService.complete(id, request);
    }

    @PostMapping("/move-outs/{id}/refund")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Pay out the refund due of a settled move-out (idempotent: Idempotency-Key header required)")
    public MoveOutResponse refund(@PathVariable Long id,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @Valid @RequestBody RefundRequest request) {
        return moveOutService.refund(id, key, request);
    }

    @PostMapping("/move-outs/{id}/close")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Close a settled case; writeOffDues=true writes off what the resident still owes")
    public MoveOutResponse close(@PathVariable Long id, @Valid @RequestBody CloseRequest request) {
        return moveOutService.close(id, request);
    }
}
