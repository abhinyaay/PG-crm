package com.pgms.complaint;

import com.pgms.common.api.PageResponse;
import com.pgms.common.domain.Priority;
import com.pgms.complaint.ComplaintDtos.ActivityResponse;
import com.pgms.complaint.ComplaintDtos.AssignComplaintRequest;
import com.pgms.complaint.ComplaintDtos.CommentRequest;
import com.pgms.complaint.ComplaintDtos.CommentResponse;
import com.pgms.complaint.ComplaintDtos.ComplaintResponse;
import com.pgms.complaint.ComplaintDtos.ComplaintStatusRequest;
import com.pgms.complaint.ComplaintDtos.CreateComplaintRequest;
import com.pgms.complaint.ComplaintDtos.EscalateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/complaints")
@Tag(name = "Complaints")
public class ComplaintController {

    private final ComplaintService service;

    public ComplaintController(ComplaintService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ComplaintResponse> create(@Valid @RequestBody CreateComplaintRequest request) {
        ComplaintResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/complaints/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<ComplaintResponse> search(@RequestParam(required = false) Long propertyId,
                                                  @RequestParam(required = false) ComplaintStatus status,
                                                  @RequestParam(required = false) ComplaintCategory category,
                                                  @RequestParam(required = false) Priority priority,
                                                  @RequestParam(required = false) Integer minEscalationLevel,
                                                  @RequestParam(defaultValue = "false") boolean assignedToMe,
                                                  Pageable pageable) {
        return service.search(propertyId, status, category, priority, minEscalationLevel, assignedToMe, pageable);
    }

    @GetMapping("/{id}")
    public ComplaintResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    public ComplaintResponse assign(@PathVariable Long id, @Valid @RequestBody AssignComplaintRequest request) {
        return service.assign(id, request.assigneeUserId());
    }

    @PostMapping("/{id}/status")
    public ComplaintResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ComplaintStatusRequest request) {
        return service.changeStatus(id, request.status(), request.resolution());
    }

    @PostMapping("/{id}/escalate")
    @Operation(summary = "Escalate a complaint one level (tenants only once the deadline has passed)")
    public ComplaintResponse escalate(@PathVariable Long id, @Valid @RequestBody EscalateRequest request) {
        return service.escalate(id, request.reason());
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@PathVariable Long id, @Valid @RequestBody CommentRequest request) {
        return service.addComment(id, request.body(), request.internal());
    }

    @GetMapping("/{id}/comments")
    public List<CommentResponse> comments(@PathVariable Long id) {
        return service.comments(id);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Audit timeline of the complaint")
    public List<ActivityResponse> history(@PathVariable Long id) {
        return service.history(id);
    }
}
