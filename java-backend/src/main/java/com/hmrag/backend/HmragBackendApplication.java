package com.hmrag.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude = FlywayAutoConfiguration.class)
public class HmragBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmragBackendApplication.class, args);
    }
}
