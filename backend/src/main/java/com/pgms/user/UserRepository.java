package com.pgms.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("select count(u) > 0 from User u where u.role.name = :roleName")
    boolean existsByRoleName(@Param("roleName") RoleName roleName);

    @Query("""
            select u from User u
            where u.organizationId = :organizationId and u.role.name in :roles and u.status = com.pgms.user.UserStatus.ACTIVE
            """)
    List<User> findActiveByOrganizationAndRoles(@Param("organizationId") Long organizationId,
                                                @Param("roles") Collection<RoleName> roles);

    /** Active accounts whose own phone matches one of the given spellings (forgot-password by phone). */
    @Query("select u from User u where u.phone in :phones and u.status = com.pgms.user.UserStatus.ACTIVE")
    List<User> findActiveByPhoneIn(@Param("phones") Collection<String> phones);

    /** Active resident logins whose tenant profile has one of the given phone spellings. */
    @Query("""
            select u from User u
            where u.status = com.pgms.user.UserStatus.ACTIVE
              and u.id in (select t.userId from Tenant t where t.phone in :phones and t.userId is not null)
            """)
    List<User> findActiveByTenantPhoneIn(@Param("phones") Collection<String> phones);

    @Query("""
            select u from User u
            where u.id in (select pa.userId from PropertyAssignment pa where pa.propertyId = :propertyId)
              and u.role.name in :roles and u.status = com.pgms.user.UserStatus.ACTIVE
            """)
    List<User> findActiveAssignedToProperty(@Param("propertyId") Long propertyId,
                                            @Param("roles") Collection<RoleName> roles);
}
