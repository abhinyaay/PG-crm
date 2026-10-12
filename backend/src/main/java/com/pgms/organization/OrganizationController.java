package com.pgms.organization;

import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.security.AccessGuard;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-level onboarding of PG operators. Thin enough that a separate service class would add nothing. */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations")
public class OrganizationController {

    private static final Set<String> SORTABLE = Set.of("name", "createdAt");

    private final OrganizationRepository repository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public OrganizationController(OrganizationRepository repository, AccessGuard accessGuard, AuditService auditService) {
        this.repository = repository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    public record OrganizationRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{3,80}$", message = "lowercase letters, digits and hyphens") String slug,
            @Email String contactEmail,
            @Size(max = 20) String contactPhone) {
    }

    public record StatusRequest(@NotNull OrganizationStatus status) {
    }

    public record OrganizationResponse(Long id, String name, String slug, String contactEmail, String contactPhone,
                                       OrganizationStatus status, Instant createdAt) {
        static OrganizationResponse of(Organization o) {
            return new OrganizationResponse(o.getId(), o.getName(), o.getSlug(), o.getContactEmail(), o.getContactPhone(),
                    o.getStatus(), o.getCreatedAt());
        }
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody OrganizationRequest request) {
        if (repository.existsBySlug(request.slug())) {
            throw new ConflictException("ORGANIZATION_EXISTS", "Organization slug already in use");
        }
        Organization org = repository.save(new Organization(request.name(), request.slug(), request.contactEmail(),
                request.contactPhone()));
        auditService.record(org.getId(), "ORGANIZATION_CREATED", "ORGANIZATION", org.getId(), Map.of("slug", org.getSlug()));
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + org.getId())).body(OrganizationResponse.of(org));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> list(Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by("name"));
        return PageResponse.from(repository.findAll(page), OrganizationResponse::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')")
    @Transactional(readOnly = true)
    public OrganizationResponse get(@PathVariable Long id) {
        accessGuard.requireOrganizationAccess(id);
        return OrganizationResponse.of(load(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public OrganizationResponse update(@PathVariable Long id, @Valid @RequestBody OrganizationRequest request) {
        Organization org = load(id);
        org.update(request.name(), request.contactEmail(), request.contactPhone());
        auditService.record(id, "ORGANIZATION_UPDATED", "ORGANIZATION", id);
        return OrganizationResponse.of(org);
    }

    /** Suspending blocks new logins and token refreshes for every account of the organization. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public OrganizationResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        Organization org = load(id);
        org.changeStatus(request.status());
        auditService.record(id, "ORGANIZATION_STATUS_CHANGED", "ORGANIZATION", id, Map.of("status", request.status().name()));
        return OrganizationResponse.of(org);
    }

    private Organization load(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Organization", id));
    }
}
