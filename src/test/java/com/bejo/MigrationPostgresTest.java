package com.bejo;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole app against a REAL PostgreSQL with Flyway on and Hibernate's
 * {@code ddl-auto=validate}.
 *
 * <p>Every other test runs on in-memory H2 whose tables are generated from the entities, so none
 * of them can notice a drift between an entity and the migration SQL, a migration that doesn't
 * apply, or a Postgres-only construct (schemas, partial indexes). This one can: if V1 doesn't apply
 * cleanly, or any entity maps a table/column the migrations don't create, the context fails to
 * start and the test with it. {@code CLAUDE.md} used to rely on someone remembering to do this by hand.
 *
 * <p>Skipped unless {@code MIGRATION_TEST_DB_URL} is set, so {@code mvn test} still works with no
 * database. CI provides a Postgres service container (ci.yml). Locally:
 * <pre>
 *   podman run -d --name bejo-it-pg -p 5432:5432 -e POSTGRES_DB=bejo -e POSTGRES_USER=bejo \
 *       -e POSTGRES_PASSWORD=bejo postgres:17-alpine
 *   MIGRATION_TEST_DB_URL=jdbc:postgresql://localhost:5432/bejo ./mvnw test -Dtest=MigrationPostgresTest
 * </pre>
 * Use an empty database: it runs the migrations. The test profile is deliberately NOT active.
 */
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${MIGRATION_TEST_DB_URL}",
        "spring.datasource.username=${MIGRATION_TEST_DB_USER:bejo}",
        "spring.datasource.password=${MIGRATION_TEST_DB_PASSWORD:bejo}",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class MigrationPostgresTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConfigurableApplicationContext context;
    // Injected only to force the EntityManagerFactory — and with it the schema validation — to be
    // created, rather than relying on something else having asked for it under lazy initialization.
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void migrationsApplyAndEveryEntityMatchesTheSchema() {
        assertThat(entityManagerFactory.isOpen()).isTrue();

        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);

        List<String> schemas = jdbc.queryForList(
                "select distinct table_schema from information_schema.tables "
                        + "where table_schema in ('core', 'commerce', 'ads')", String.class);
        assertThat(schemas).containsExactlyInAnyOrder("core", "commerce", "ads");
    }

    @Test
    void migrationsRunAtStartupEvenWithLazyInitialization() {
        // spring.main.lazy-initialization is on; EagerMigrationConfig must keep Flyway out of it
        // so a broken migration fails the deploy instead of the first request.
        assertThat(context.getBeanFactory().getBeanDefinition("flywayInitializer").isLazyInit()).isFalse();
    }
}
