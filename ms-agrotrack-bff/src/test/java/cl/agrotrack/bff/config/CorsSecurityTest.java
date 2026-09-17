package cl.agrotrack.bff.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de seguridad para validación de CORS seguro en el BFF.
 * Valida la obligatoriedad de protocolo seguro HTTPS en entornos de producción (CWE-942 / CWE-346).
 */
class CorsSecurityTest {

    @Test
    @DisplayName("Test 1: En entorno de producción, los orígenes HTTP no seguros son rechazados")
    void origenesInsegurosRechazadosEnProduccion() {
        String origenesMixtos = "http://inseguro.agrotrack.cl, https://app.agrotrack.cl, http://malicioso.com";
        SecurityConfig configProd = new SecurityConfig(origenesMixtos, "prod");

        assertThat(configProd.isEsProduccion()).isTrue();
        assertThat(configProd.getOrigenesCors()).hasSize(1);
        assertThat(configProd.getOrigenesCors()).containsExactly("https://app.agrotrack.cl");
        assertThat(configProd.getOrigenesCors()).noneMatch(o -> o.startsWith("http://"));
    }

    @Test
    @DisplayName("Test 2: En entorno local/desarrollo, se permite HTTP para el frontend Angular local")
    void origenesHttpPermitidosEnLocal() {
        String origenesDev = "http://localhost:4200, http://127.0.0.1:4200";
        SecurityConfig configLocal = new SecurityConfig(origenesDev, "local");

        assertThat(configLocal.isEsProduccion()).isFalse();
        assertThat(configLocal.getOrigenesCors()).contains("http://localhost:4200", "http://127.0.0.1:4200");
    }

    @Test
    @DisplayName("Test 4: El perfil aws (el del despliegue real) también exige HTTPS")
    void perfilAwsEsProduccion() {
        SecurityConfig configAws = new SecurityConfig(
                "https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com, http://54.84.179.128", "aws");

        assertThat(configAws.isEsProduccion()).isTrue();
        assertThat(configAws.getOrigenesCors()).containsExactly("https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com");
    }

    @Test
    @DisplayName("Test 5: Con varios perfiles activos basta con que uno sea de producción")
    void variosPerfiles() {
        assertThat(new SecurityConfig("https://a.cl", "debug, aws").isEsProduccion()).isTrue();
        assertThat(new SecurityConfig("http://localhost:4200", "local,debug").isEsProduccion()).isFalse();
    }

    @Test
    @DisplayName("Test 3: En producción con solo orígenes HTTPS, todos los orígenes seguros son admitidos")
    void soloOrigenesHttpsAceptadosEnProduccion() {
        String origenesHttps = "https://agrotrack.cl, https://portal.agrotrack.cl";
        SecurityConfig configProd = new SecurityConfig(origenesHttps, "production");

        assertThat(configProd.isEsProduccion()).isTrue();
        assertThat(configProd.getOrigenesCors()).containsExactlyInAnyOrder(
                "https://agrotrack.cl",
                "https://portal.agrotrack.cl"
        );
    }
}
