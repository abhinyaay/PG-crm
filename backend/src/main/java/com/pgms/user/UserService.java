package com.pgms.user;

import com.pgms.audit.AuditService;
import com.pgms.auth.AuthService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.ForbiddenException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.organization.OrganizationRepository;
import com.pgms.property.PropertyAssignment;
import com.pgms.property.PropertyAssignmentRepository;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUser;
import com.pgms.user.UserDtos.CreateUserRequest;
import com.pgms.user.UserDtos.UpdateUserRequest;
import com.pgms.user.UserDtos.UserResponse;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff/admin account management. Who may manage whom:
 * SUPER_ADMIN - any account in any organization; PROPERTY_ADMIN - MANAGER/STAFF/TENANT accounts in their own
 * organization. Tenant portal accounts are normally created through the tenant module.
 */
@Service
public class UserService {

    private static final Set<String> SORTABLE = Set.of("fullName", "email", "createdAt", "lastLoginAt");
    private static final Set<RoleName> MANAGEABLE_BY_PROPERTY_ADMIN = EnumSet.of(RoleName.MANAGER, RoleName.STAFF, RoleName.TENANT);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessGuard accessGuard;
    private final AuthService authService;
    private final AuditService auditService;
    private final Clock clock;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       OrganizationRepository organizationRepository, PropertyRepository propertyRepository,
                       PropertyAssignmentRepository assignmentRepository, PasswordEncoder passwordEncoder,
                       AccessGuard accessGuard, AuthService authService, AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.organizationRepository = organizationRepository;
        this.propertyRepository = propertyRepository;
        this.assignmentRepository = assignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessGuard = accessGuard;
        this.authService = authService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        CurrentUser actor = accessGuard.currentUser();
        Long organizationId = request.role() == RoleName.SUPER_ADMIN ? null
                : accessGuard.resolveOrganizationForCreate(request.organizationId());
        requireCanManage(actor, request.role(), organizationId);
        if (organizationId != null && !organizationRepository.existsById(organizationId)) {
            throw new NotFoundException("Organization", organizationId);
        }
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists");
        }
        Role role = roleRepository.findByName(request.role()).orElseThrow(() -> new NotFoundException("Role", request.role()));
        User user = userRepository.save(new User(organizationId, role, email, passwordEncoder.encode(request.password()),
                request.fullName(), request.phone()));

        Set<Long> propertyIds = request.propertyIds() == null ? Set.of() : request.propertyIds();
        if (!propertyIds.isEmpty()) {
            if (!request.role().isPropertyScoped()) {
                throw new BusinessRuleException("ASSIGNMENT_NOT_APPLICABLE", "Only MANAGER and STAFF users are assigned to properties");
            }
            for (Long propertyId : propertyIds) {
                Long propertyOrg = propertyRepository.findOrganizationIdById(propertyId)
                        .orElseThrow(() -> new NotFoundException("Property", propertyId));
                if (!propertyOrg.equals(organizationId)) {
                    throw new NotFoundException("Property", propertyId);
                }
                assignmentRepository.save(new PropertyAssignment(propertyId, user.getId(), clock.instant(), actor.id()));
            }
        }
        auditService.record(organizationId, "USER_CREATED", "USER", user.getId(),
                Map.of("role", request.role().name(), "email", email));
        return toResponse(user, propertyIds);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        User user = loadManageable(id);
        return toResponse(user, assignmentRepository.findPropertyIdsByUserId(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(Long organizationId, RoleName role, UserStatus status, String q, Pageable pageable) {
        CurrentUser actor = accessGuard.currentUser();
        Long scopeOrg = actor.isSuperAdmin() ? organizationId : actor.organizationId();
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (scopeOrg != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("organizationId"), scopeOrg));
        }
        if (role != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role").get("name"), role));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), like), cb.like(root.get("email"), like)));
        }
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by("fullName"));
        return PageResponse.from(userRepository.findAll(spec, page), u -> toResponse(u, null));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = loadManageable(id);
        user.updateProfile(request.fullName(), request.phone());
        auditService.record(user.getOrganizationId(), "USER_UPDATED", "USER", id);
        return toResponse(user, null);
    }

    @Transactional
    public UserResponse activate(Long id) {
        User user = loadManageable(id);
        user.activate();
        auditService.record(user.getOrganizationId(), "USER_ACTIVATED", "USER", id);
        return toResponse(user, null);
    }

    /** Deactivation takes effect immediately: refresh tokens are revoked and live access tokens invalidated. */
    @Transactional
    public UserResponse deactivate(Long id) {
        User user = loadManageable(id);
        requireNotSelf(id);
        user.deactivate();
        authService.logoutEverywhere(id);
        auditService.record(user.getOrganizationId(), "USER_DEACTIVATED", "USER", id);
        return toResponse(user, null);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        User user = loadManageable(id);
        user.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        authService.passwordChanged(user, "ADMIN_RESET");
        auditService.record(user.getOrganizationId(), "PASSWORD_RESET", "USER", id);
    }

    @Transactional
    public void delete(Long id) {
        User user = loadManageable(id);
        requireNotSelf(id);
        user.softDelete(clock.instant());
        assignmentRepository.deleteByUserId(id);
        authService.logoutEverywhere(id);
        auditService.record(user.getOrganizationId(), "USER_DELETED", "USER", id);
    }

    private User loadManageable(Long id) {
        CurrentUser actor = accessGuard.currentUser();
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (!actor.isSuperAdmin() && !Objects.equals(user.getOrganizationId(), actor.organizationId())) {
            throw new NotFoundException("User", id);
        }
        requireCanManage(actor, user.getRoleName(), user.getOrganizationId());
        return user;
    }

    private void requireNotSelf(Long id) {
        if (accessGuard.currentUser().id().equals(id)) {
            throw new BusinessRuleException("SELF_OPERATION_NOT_ALLOWED", "You cannot perform this action on your own account");
        }
    }

    private static void requireCanManage(CurrentUser actor, RoleName targetRole, Long targetOrganizationId) {
        if (actor.isSuperAdmin()) {
            return;
        }
        boolean allowed = actor.is(RoleName.PROPERTY_ADMIN)
                && MANAGEABLE_BY_PROPERTY_ADMIN.contains(targetRole)
                && Objects.equals(actor.organizationId(), targetOrganizationId);
        if (!allowed) {
            throw new ForbiddenException("You cannot manage " + targetRole + " accounts");
        }
    }

    static UserResponse toResponse(User user, Set<Long> propertyIds) {
        return new UserResponse(user.getId(), user.getOrganizationId(), user.getEmail(), user.getFullName(), user.getPhone(),
                user.getRoleName(), user.getStatus(), user.getLastLoginAt(), user.getCreatedAt(), propertyIds);
    }
}
