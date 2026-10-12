package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.property.PropertyCache;
import com.pgms.property.PropertyDtos.AddressDto;
import com.pgms.property.PropertyDtos.UpdatePropertyRequest;
import com.pgms.property.PropertyService;
import com.pgms.property.PropertyType;
import com.pgms.security.JwtClaimNames;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cache eviction must happen after the updating transaction commits. Evicting earlier opens a window in which a
 * concurrent reader misses the cache, reads the still-committed old row and caches it again - stale for the whole
 * TTL (10 minutes) although the update succeeded.
 */
class PropertyCacheIT extends AbstractIntegrationTest {

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private PropertyCache propertyCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readDuringAnUncommittedUpdateDoesNotLeaveTheOldValueCached() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long admin = fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        long propertyId = layout.propertyId();
        String oldName = api.get("/api/v1/properties/" + propertyId, token).body().get("name").asText();

        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(String.valueOf(admin))
                .claim(JwtClaimNames.ROLE, "PROPERTY_ADMIN").claim(JwtClaimNames.ORGANIZATION, org.id())
                .claim(JwtClaimNames.EMAIL, org.email("admin")).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_ADMIN"))));
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                propertyService.update(propertyId, new UpdatePropertyRequest("Renamed House", null, PropertyType.CO_LIVING,
                        new AddressDto("1 Test Road", null, "Bengaluru", "Karnataka", "560001", null), null, 5));
                // a concurrent request reads the property before the update commits
                try {
                    assertThat(reader.submit(() -> propertyCache.get(propertyId).name()).get(10, TimeUnit.SECONDS))
                            .isEqualTo(oldName);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            reader.shutdownNow();
        }

        assertThat(api.get("/api/v1/properties/" + propertyId, token).body().get("name").asText()).isEqualTo("Renamed House");
    }
}
