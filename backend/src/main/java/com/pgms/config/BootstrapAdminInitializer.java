package com.pgms.config;

import com.pgms.user.Role;
import com.pgms.user.RoleName;
import com.pgms.user.RoleRepository;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates the first platform administrator from environment variables (APP_BOOTSTRAP_SUPER_ADMIN_EMAIL /
 * _PASSWORD) if no SUPER_ADMIN exists yet. No credentials are hard-coded or committed.
 */
@Component
@Order(1)
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Bootstrap bootstrap;

    public BootstrapAdminInitializer(UserRepository userRepository, RoleRepository roleRepository,
                                     PasswordEncoder passwordEncoder, AppProperties properties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrap = properties.bootstrap();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRoleName(RoleName.SUPER_ADMIN)) {
            return;
        }
        if (!StringUtils.hasText(bootstrap.superAdminEmail()) || !StringUtils.hasText(bootstrap.superAdminPassword())) {
            log.warn("No SUPER_ADMIN exists and no bootstrap credentials are configured; set "
                    + "APP_BOOTSTRAP_SUPER_ADMIN_EMAIL and APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD to create one");
            return;
        }
        Role role = roleRepository.findByName(RoleName.SUPER_ADMIN).orElseThrow();
        userRepository.save(new User(null, role, bootstrap.superAdminEmail(),
                passwordEncoder.encode(bootstrap.superAdminPassword()), "Platform Administrator", null));
        log.info("Bootstrap SUPER_ADMIN account created for {}", User.normalizeEmail(bootstrap.superAdminEmail()));
    }
}
