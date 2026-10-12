package com.pgms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@ConfigurationPropertiesScan
public class PgmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgmsApplication.class, args);
    }
}
