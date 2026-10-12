package com.pgms.tenant;

import com.pgms.audit.AuditService;
import com.pgms.auth.PasswordResetService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.TenantDtos.AddDocumentRequest;
import com.pgms.tenant.TenantDtos.CreateTenantRequest;
import com.pgms.tenant.TenantDtos.DocumentResponse;
import com.pgms.tenant.TenantDtos.EmergencyContactDto;
import com.pgms.tenant.TenantDtos.TenantResponse;
import com.pgms.tenant.TenantDtos.TenantSummary;
import com.pgms.tenant.TenantDtos.UpdateTenantRequest;
import com.pgms.user.Role;
import com.pgms.user.RoleName;
import com.pgms.user.RoleRepository;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private static final Set<String> SORTABLE = Set.of("fullName", "joiningDate", "moveOutDate", "createdAt", "status");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepository;
    private final TenantDocumentRepository documentRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetService passwordResetService;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public TenantService(TenantRepository tenantRepository, TenantDocumentRepository documentRepository,
                         PropertyRepository propertyRepository, UserRepository userRepository, RoleRepository roleRepository,
                         PasswordEncoder passwordEncoder, PasswordResetService passwordResetService, AccessGuard accessGuard,
                         AuditService auditService, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.documentRepository = documentRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetService = passwordResetService;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        accessGuard.requirePropertyAccess(request.propertyId());
        Long organizationId = propertyRepository.findOrganizationIdById(request.propertyId())
                .orElseThrow(() -> new NotFoundException("Property", request.propertyId()));
        if (tenantRepository.existsByOrganizationIdAndPhone(organizationId, request.phone())) {
            throw new ConflictException("TENANT_PHONE_EXISTS", "A tenant with this phone already exists");
        }
        Tenant tenant = new Tenant(organizationId, request.propertyId(), request.fullName(), request.email(), request.phone());
        tenant.updateProfile(request.fullName(), request.email(), request.phone(), request.gender(), request.dateOfBirth(),
                request.occupation(), request.organizationName(), request.permanentAddress());
        if (request.emergencyContacts() != null) {
            requireSinglePrimary(request.emergencyContacts());
            tenant.replaceEmergencyContacts(toContacts(tenant, request.emergencyContacts()));
        }
        tenantRepository.save(tenant);
        auditService.record(organizationId, "TENANT_CREATED", "TENANT", tenant.getId(), Map.of("propertyId", request.propertyId()));
        return TenantResponse.of(tenant);
    }

    @Transactional(readOnly = true)
    public TenantResponse get(Long id) {
        return TenantResponse.of(loadAccessible(id));
    }

    @Transactional(readOnly = true)
    public TenantResponse me() {
        return TenantResponse.of(accessGuard.requireCurrentTenant());
    }

    @Transactional(readOnly = true)
    public PageResponse<TenantSummary> search(Long propertyId, TenantStatus status, String q, LocalDate joinedFrom,
                                              LocalDate joinedTo, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by("fullName"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<Tenant>empty(page), TenantSummary::of);
        }
        Specification<Tenant> spec = scope.toSpecification();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            String term = q.trim().toLowerCase();
            String like = "%" + term + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), like),   // served by the pg_trgm GIN index
                    cb.like(root.get("email"), like),
                    cb.like(root.get("phone"), like)));
        }
        if (joinedFrom != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("joiningDate"), joinedFrom));
        }
        if (joinedTo != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("joiningDate"), joinedTo));
        }
        return PageResponse.from(tenantRepository.findAll(spec, page), TenantSummary::of);
    }

    @Transactional
    public TenantResponse update(Long id, UpdateTenantRequest request) {
        Tenant tenant = loadAccessible(id);
        tenant.updateProfile(request.fullName(), request.email(), request.phone(), request.gender(), request.dateOfBirth(),
                request.occupation(), request.organizationName(), request.permanentAddress());
        auditService.record(tenant.getOrganizationId(), "TENANT_UPDATED", "TENANT", id);
        return TenantResponse.of(tenant);
    }

    @Transactional
    public TenantResponse replaceEmergencyContacts(Long id, List<EmergencyContactDto> contacts) {
        Tenant tenant = loadAccessible(id);
        requireSinglePrimary(contacts);
        tenant.replaceEmergencyContacts(toContacts(tenant, contacts));
        auditService.record(tenant.getOrganizationId(), "TENANT_CONTACTS_UPDATED", "TENANT", id);
        return TenantResponse.of(tenant);
    }

    @Transactional
    public void delete(Long id) {
        Tenant tenant = loadAccessible(id);
        tenant.softDelete(clock.instant());
        auditService.record(tenant.getOrganizationId(), "TENANT_DELETED", "TENANT", id);
    }

    // ---- KYC documents ----

    @Transactional
    public DocumentResponse addDocument(Long tenantId, AddDocumentRequest request) {
        Tenant tenant = loadAccessible(tenantId);
        TenantDocument document = documentRepository.save(
                new TenantDocument(tenantId, request.documentType(), request.documentNumber(), request.storageKey()));
        auditService.record(tenant.getOrganizationId(), "TENANT_DOCUMENT_ADDED", "TENANT", tenantId,
                Map.of("documentType", request.documentType().name()));
        return DocumentResponse.of(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> documents(Long tenantId) {
        loadAccessible(tenantId);
        return documentRepository.findByTenantIdOrderByCreatedAt(tenantId).stream().map(DocumentResponse::of).toList();
    }

    @Transactional
    public DocumentResponse reviewDocument(Long tenantId, Long documentId, VerificationStatus status) {
        Tenant tenant = loadAccessible(tenantId);
        TenantDocument document = documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Document", documentId));
        document.review(status, accessGuard.currentUser().id(), clock.instant());
        auditService.record(tenant.getOrganizationId(), "TENANT_DOCUMENT_REVIEWED", "TENANT", tenantId,
                Map.of("documentId", documentId, "status", status.name()));
        return DocumentResponse.of(document);
    }

    /**
     * Creates a TENANT login for the resident, using their email as username. Without {@code initialPassword} (the
     * recommended way) the account gets an unusable random password and the resident receives a "set your password"
     * code by email and SMS; staff never learn the password.
     */
    @Transactional
    public TenantResponse createPortalAccount(Long tenantId, String initialPassword) {
        Tenant tenant = loadAccessible(tenantId);
        // a link to a login that was since deleted (users are soft-deleted) does not count: allow a new account
        if (tenant.getUserId() != null && userRepository.existsById(tenant.getUserId())) {
            throw new ConflictException("PORTAL_ACCOUNT_EXISTS", "Tenant already has a portal account");
        }
        if (tenant.getEmail() == null) {
            throw new BusinessRuleException("EMAIL_REQUIRED", "Tenant needs an email address to get a portal account");
        }
        if (userRepository.existsByEmail(tenant.getEmail())) {
            throw new ConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists");
        }
        Role role = roleRepository.findByName(RoleName.TENANT).orElseThrow(() -> new NotFoundException("Role", RoleName.TENANT));
        boolean invite = initialPassword == null || initialPassword.isBlank();
        String password = invite ? randomUnusablePassword() : initialPassword;
        User user = userRepository.save(new User(tenant.getOrganizationId(), role, tenant.getEmail(),
                passwordEncoder.encode(password), tenant.getFullName(), tenant.getPhone()));
        tenant.linkUser(user.getId());
        if (invite) {
            passwordResetService.issueSetupCode(user);
        }
        auditService.record(tenant.getOrganizationId(), "TENANT_PORTAL_ACCOUNT_CREATED", "TENANT", tenantId,
                Map.of("userId", user.getId(), "setupCodeSent", invite));
        return TenantResponse.of(tenant);
    }

    /** Loads a tenant enforcing data-level access (a TENANT caller may only load themselves). */
    @Transactional(readOnly = true)
    public Tenant loadAccessible(Long id) {
        Tenant tenant = tenantRepository.findById(id).orElseThrow(() -> new NotFoundException("Tenant", id));
        if (accessGuard.isTenant()) {
            if (!tenant.getId().equals(accessGuard.requireCurrentTenant().getId())) {
                throw new NotFoundException("Tenant", id);
            }
            return tenant;
        }
        accessGuard.requirePropertyAccess(tenant.getPropertyId());
        return tenant;
    }

    /** 256 random bits nobody is told (bcrypt reads at most 72 bytes, so keep it short). */
    private static String randomUnusablePassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireSinglePrimary(List<EmergencyContactDto> contacts) {
        if (contacts.stream().filter(EmergencyContactDto::primary).count() > 1) {
            throw new BusinessRuleException("MULTIPLE_PRIMARY_CONTACTS", "Only one emergency contact can be primary");
        }
    }

    private static List<EmergencyContact> toContacts(Tenant tenant, List<EmergencyContactDto> dtos) {
        return dtos.stream()
                .map(c -> new EmergencyContact(tenant, c.name(), c.relationship(), c.phone(), c.email(), c.primary()))
                .toList();
    }
}
