package cl.agrotrack.audit.soporte;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL real, una vez por JVM. Los listeners de Kafka no arrancan:
 * aqui se prueba la logica de registro y consulta, no el transporte.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
public abstract class PostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agro_audit")
            .withUsername("agro_audit")
            .withPassword("agro_audit");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
