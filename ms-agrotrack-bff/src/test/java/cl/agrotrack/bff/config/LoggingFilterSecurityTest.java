package cl.agrotrack.bff.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de seguridad para el filtro de Logging seguro (LoggingFilter).
 * Valida que credenciales, contraseñas y tokens nunca se expongan en texto claro (CWE-532).
 */
class LoggingFilterSecurityTest {

    private final LoggingFilter loggingFilter = new LoggingFilter();

    @Test
    @DisplayName("Test 1: Parámetros sensibles en query string son enmascarados (password, token, secret)")
    void sanitizaParametrosSensiblesEnQueryString() {
        String queryInsegura = "status=RECIBIDA&token=eyJhGciOi...12345&password=SuperSecretPass!&page=1&secret=miClavePrivada";

        String sanitizada = LoggingFilter.sanitizarQueryString(queryInsegura);

        assertThat(sanitizada).doesNotContain("eyJhGciOi...12345");
        assertThat(sanitizada).doesNotContain("SuperSecretPass!");
        assertThat(sanitizada).doesNotContain("miClavePrivada");

        assertThat(sanitizada).contains("token=***");
        assertThat(sanitizada).contains("password=***");
        assertThat(sanitizada).contains("secret=***");

        // Parámetros legítimos no sensibles permanecen intactos
        assertThat(sanitizada).contains("status=RECIBIDA");
        assertThat(sanitizada).contains("page=1");
    }

    @Test
    @DisplayName("Test 2: Cabecera Authorization es enmascarada a Bearer ***")
    void sanitizaCabeceraAuthorization() {
        String bearerTokenReal = "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkxpYW4iLCJpYXQiOjE1MTYyMzkwMjJ9";

        String sanitizada = LoggingFilter.sanitizarCabecera("Authorization", bearerTokenReal);

        assertThat(sanitizada).isEqualTo("Bearer ***");
        assertThat(sanitizada).doesNotContain("eyJhbGciOiJSUzI1Ni");
    }

    @Test
    @DisplayName("Test 3: Filtro procesa la petición HTTP exitosamente sin arrojar excepciones")
    void filtroEjecutaCadenaSinInterrupcion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
        request.setQueryString("status=RECIBIDA&token=secreto999");
        request.addHeader("Authorization", "Bearer eyJtokenPrivado");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        loggingFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
