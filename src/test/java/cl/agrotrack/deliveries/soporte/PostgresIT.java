package cl.agrotrack.deliveries.soporte;

import cl.agrotrack.deliveries.aplicacion.CatalogClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de los tests de integracion: PostgreSQL real en Docker, una sola vez
 * por JVM. Catalog se reemplaza por un mock: aqui se prueba deliveries.
 */
@SpringBootTest(properties = "agrotrack.mensajeria.enabled=false")
public abstract class PostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agro_deliveries")
            .withUsername("agro_deliveries")
            .withPassword("agro_deliveries");

    static {
        POSTGRES.start();
    }

    @MockitoBean
    protected CatalogClient catalog;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
