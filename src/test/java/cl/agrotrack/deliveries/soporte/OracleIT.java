package cl.agrotrack.deliveries.soporte;

import cl.agrotrack.deliveries.aplicacion.CatalogClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.oracle.OracleContainer;

/**
 * Base de los tests de integracion: Oracle real en Docker, una sola vez
 * por JVM. Catalog se reemplaza por un mock: aqui se prueba deliveries.
 */
@SpringBootTest
public abstract class OracleIT {

    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim")
            .withUsername("agro_deliveries")
            .withPassword("agro_deliveries");

    static {
        ORACLE.start();
    }

    @MockitoBean
    protected CatalogClient catalog;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
    }
}
