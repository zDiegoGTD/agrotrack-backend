package cl.agrotrack.catalog.soporte;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;

/**
 * Base de los tests de integracion: un Oracle real en Docker.
 *
 * <p>El contenedor es estatico y se arranca una sola vez por JVM, compartido
 * por todas las clases que heredan de aqui. Oracle tarda ~40 s en levantar;
 * pagarlo una vez por corrida es aceptable, una vez por clase no.
 *
 * <p>Se usa la misma imagen que infra/local/compose.yml: lo que pasa en el
 * test es lo que pasa en desarrollo.
 */
@SpringBootTest
public abstract class OracleIT {

    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim")
            .withUsername("agro_catalog")
            .withPassword("agro_catalog");

    static {
        ORACLE.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
    }
}
