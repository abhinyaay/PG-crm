package com.pgms.access;

import com.pgms.access.AccessDtos.CreateCredentialRequest;
import com.pgms.access.AccessDtos.CredentialResponse;
import com.pgms.access.AccessDtos.NextPinResponse;
import com.pgms.audit.AuditService;
import com.pgms.booking.BookingEvents;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyAssignmentRepository;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enrolment: which device user id (PIN) belongs to which resident or staff member at a property. */
@Service
public class AccessCredentialService {

    private static final Set<String> SORTABLE = Set.of("deviceUserId", "enrolledAt", "status");

    private final AccessCredentialRepository credentialRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public AccessCredentialService(AccessCredentialRepository credentialRepository, PropertyRepository propertyRepository,
                                   PropertyAssignmentRepository assignmentRepository, TenantRepository tenantRepository,
                                   UserRepository userRepository, AccessGuard accessGuard, AuditService auditService,
                                   Clock clock) {
        this.credentialRepository = credentialRepository;
        this.propertyRepository = propertyRepository;
        this.assignmentRepository = assignmentRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CredentialResponse> list(Long propertyId, Long tenantId, CredentialStatus status, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by("deviceUserId"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<AccessCredential>empty(page), c -> CredentialResponse.of(c, null));
        }
        Specification<AccessCredential> spec = scope.toPropertySpecification();
        if (tenantId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("tenantId"), tenantId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Page<AccessCredential> result = credentialRepository.findAll(spec, page);
        Map<String, String> names = names(result.getContent());
        return PageResponse.from(result, c -> CredentialResponse.of(c, names.get(key(c))));
    }

    @Transactional(readOnly = true)
    public NextPinResponse nextPin(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        return new NextPinResponse(propertyId, suggestPin(propertyId));
    }

    @Transactional
    public CredentialResponse create(CreateCredentialRequest request) {
        accessGuard.requirePropertyAccess(request.propertyId());
        Long organizationId = propertyRepository.findOrganizationIdById(request.propertyId())
                .orElseThrow(() -> new NotFoundException("Property", request.propertyId()));
        if ((request.tenantId() == null) == (request.userId() == null)) {
            throw new BadRequestException("CREDENTIAL_OWNER_REQUIRED", "Give exactly one of tenantId or userId");
        }
        String personName;
        if (request.tenantId() != null) {
            Tenant tenant = tenantRepository.findById(request.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant", request.tenantId()));
            if (!Objects.equals(tenant.getPropertyId(), request.propertyId())) {
                throw new BusinessRuleException("TENANT_NOT_AT_PROPERTY", "The resident does not belong to this property");
            }
            personName = tenant.getFullName();
        } else {
            User user = userRepository.findById(request.userId()).orElseThrow(() -> new NotFoundException("User", request.userId()));
            boolean staffOfProperty = Objects.equals(user.getOrganizationId(), organizationId)
                    && (user.getRoleName() == RoleName.PROPERTY_ADMIN || user.getRoleName().isPropertyScoped()
                        && assignmentRepository.existsByPropertyIdAndUserId(request.propertyId(), user.getId()));
            if (!staffOfProperty) {
                throw new BusinessRuleException("USER_NOT_STAFF_OF_PROPERTY", "The user is not a staff member of this property");
            }
            personName = user.getFullName();
        }
        String pin = request.deviceUserId() != null ? request.deviceUserId().trim() : suggestPin(request.propertyId());
        if (credentialRepository.existsByPropertyIdAndDeviceUserIdAndStatus(request.propertyId(), pin, CredentialStatus.ACTIVE)) {
            throw new ConflictException("DEVICE_USER_ID_IN_USE", "Device user id " + pin + " is already in use at this property");
        }
        CredentialType type = request.credentialType() == null ? CredentialType.FINGERPRINT : request.credentialType();
        AccessCredential credential = credentialRepository.saveAndFlush(new AccessCredential(request.propertyId(), pin,
                request.tenantId(), request.userId(), type, clock.instant(), accessGuard.currentUser().id()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("propertyId", request.propertyId());
        details.put("deviceUserId", pin);
        details.put(request.tenantId() != null ? "tenantId" : "userId",
                request.tenantId() != null ? request.tenantId() : request.userId());
        details.put("credentialType", type.name());
        auditService.record(organizationId, "ACCESS_CREDENTIAL_CREATED", "ACCESS_CREDENTIAL", credential.getId(), details);
        return CredentialResponse.of(credential, personName);
    }

    @Transactional
    public void revoke(Long id) {
        AccessCredential credential = credentialRepository.findById(id).orElseThrow(() -> new NotFoundException("Credential", id));
        accessGuard.requirePropertyAccess(credential.getPropertyId());
        if (credential.revoke(clock.instant(), accessGuard.currentUser().id())) {
            Long organizationId = propertyRepository.findOrganizationIdById(credential.getPropertyId()).orElse(null);
            auditService.record(organizationId, "ACCESS_CREDENTIAL_REVOKED", "ACCESS_CREDENTIAL", id,
                    Map.of("deviceUserId", credential.getDeviceUserId()));
        }
    }

    /**
     * Data minimisation: when a resident checks out, their device mappings are revoked (the fingerprint should also be
     * deleted on the device). Runs inside the check-out transaction.
     */
    @EventListener
    public void on(BookingEvents.TenantCheckedOut event) {
        List<AccessCredential> active = credentialRepository.findByTenantIdAndStatus(event.tenantId(), CredentialStatus.ACTIVE);
        for (AccessCredential credential : active) {
            credential.revoke(clock.instant(), null);
            auditService.record(event.organizationId(), "ACCESS_CREDENTIAL_REVOKED", "ACCESS_CREDENTIAL", credential.getId(),
                    Map.of("deviceUserId", credential.getDeviceUserId(), "reason", "CHECKED_OUT"));
        }
    }

    private String suggestPin(Long propertyId) {
        Long max = credentialRepository.findMaxNumericPin(propertyId);
        return String.valueOf(max == null ? 1 : max + 1);
    }

    private Map<String, String> names(List<AccessCredential> credentials) {
        Set<Long> tenantIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        credentials.forEach(c -> {
            if (c.getTenantId() != null) {
                tenantIds.add(c.getTenantId());
            } else {
                userIds.add(c.getUserId());
            }
        });
        Map<String, String> names = new LinkedHashMap<>();
        tenantRepository.findAllById(tenantIds).forEach(t -> names.put("T" + t.getId(), t.getFullName()));
        userRepository.findAllById(userIds).forEach(u -> names.put("U" + u.getId(), u.getFullName()));
        return names;
    }

    private static String key(AccessCredential c) {
        return c.getTenantId() != null ? "T" + c.getTenantId() : "U" + c.getUserId();
    }
}
