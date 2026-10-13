package com.bejo.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps database migrations a startup step.
 *
 * <p>{@code spring.main.lazy-initialization} is on (to keep scale-to-zero cold starts short), which
 * defers every bean until first use — including the one that runs Flyway. Migrations were only
 * happening at startup by accident: {@code DataSeeder}, an {@code ApplicationRunner}, is always
 * instantiated and drags the JPA/Flyway chain in with it. Delete or condition that seeder and the
 * schema would silently be migrated on the first request instead, with the first caller waiting for
 * it and a broken migration no longer failing the deploy. This exclusion makes the intent explicit.
 * {@code MigrationPostgresTest} asserts it.
 */
@Configuration(proxyBeanMethods = false)
public class EagerMigrationConfig {

    @Bean
    static LazyInitializationExcludeFilter flywayRunsAtStartup() {
        return LazyInitializationExcludeFilter.forBeanTypes(FlywayMigrationInitializer.class);
    }
}
