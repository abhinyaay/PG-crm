package com.pgms.integration;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full application against real PostgreSQL 16 and Redis 7 containers. Containers are started once per
 * JVM (singleton pattern) and shared by all integration test classes so the Spring context is cached too.
 * Every test creates its own uniquely-named organization, so tests never interfere through shared data.
 * Notification delivery is real too: SMTP goes to an embedded GreenMail server and SMS to {@link SmsGatewayStub}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pgms").withUsername("pgms").withPassword("pgms");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final GreenMail SMTP = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static final SmsGatewayStub SMS_GATEWAY = new SmsGatewayStub();

    static {
        POSTGRES.start();
        REDIS.start();
        SMTP.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
        registry.add("app.notifications.sms.msg91.base-url", SMS_GATEWAY::baseUrl);
    }

    @Autowired
    protected Fixtures fixtures;

    @Autowired
    protected Api api;
}
