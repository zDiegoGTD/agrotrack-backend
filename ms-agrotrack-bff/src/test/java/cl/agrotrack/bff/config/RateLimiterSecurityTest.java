package cl.agrotrack.bff.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de seguridad para el filtro de Rate Limiting.
 * Valida la protección contra ataques de fuerza bruta y DoS (100 req/min por IP -> 429).
 */
class RateLimiterSecurityTest {

    private RateLimiterFilter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiterFilter(100, objectMapper);
        rateLimiter.limpiarContadores();
    }

    @Test
    @DisplayName("Test 1: 100 peticiones consecutivas desde la misma IP son permitidas")
    void cienPeticionesSonPermitidas() throws Exception {
        String ip = "192.168.1.10";

        for (int i = 1; i <= 100; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = new MockFilterChain();

            rateLimiter.doFilterInternal(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    @DisplayName("Test 2: La petición 101 desde la misma IP es rechazada con HTTP 429 Too Many Requests")
    void peticion101Retorna429TooManyRequests() throws Exception {
        String ip = "192.168.1.20";

        // Realizamos 100 peticiones previas
        for (int i = 1; i <= 100; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            rateLimiter.doFilterInternal(request, response, new MockFilterChain());
        }

        // Petición número 101
        MockHttpServletRequest request101 = new MockHttpServletRequest("GET", "/api/deliveries");
        request101.setRemoteAddr(ip);
        MockHttpServletResponse response101 = new MockHttpServletResponse();

        rateLimiter.doFilterInternal(request101, response101, new MockFilterChain());

        assertThat(response101.getStatus()).isEqualTo(429);
        assertThat(response101.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response101.getContentType()).isEqualTo("application/problem+json");

        String json = response101.getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("status").asInt()).isEqualTo(429);
        assertThat(node.get("title").asText()).isEqualTo("Too Many Requests");
        assertThat(node.get("detail").asText()).contains("Límite de 100 peticiones por minuto excedido");
    }

    @Test
    @DisplayName("Test 4: Inventar el primer valor de X-Forwarded-For no evade el límite")
    void xForwardedForFalsoNoEvadeElLimite() throws Exception {
        // El API Gateway AGREGA la IP real al final de lo que mande el cliente.
        // Si se tomara el primer valor, cambiarlo en cada peticion daria un
        // contador nuevo cada vez y el limite nunca se alcanzaria.
        MockHttpServletResponse ultima = null;
        for (int i = 1; i <= 101; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
            request.setRemoteAddr("10.10.0.5");
            request.addHeader("X-Forwarded-For", "1.2.3." + i + ", 203.0.113.9");
            ultima = new MockHttpServletResponse();
            rateLimiter.doFilterInternal(request, ultima, new MockFilterChain());
        }

        assertThat(ultima.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Test 3: El límite por IP es aislado y no afecta a otras direcciones IP")
    void aislamientoPorIp() throws Exception {
        String ipBloqueada = "10.0.0.1";
        String ipPermitida = "10.0.0.2";

        // Saturamos la IP bloqueada con 101 peticiones
        for (int i = 1; i <= 101; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/catalog");
            request.setRemoteAddr(ipBloqueada);
            MockHttpServletResponse response = new MockHttpServletResponse();
            rateLimiter.doFilterInternal(request, response, new MockFilterChain());
        }

        // Una petición desde otra IP debe permitirse con normalidad
        MockHttpServletRequest requestNuevaIp = new MockHttpServletRequest("GET", "/api/catalog");
        requestNuevaIp.setRemoteAddr(ipPermitida);
        MockHttpServletResponse responseNuevaIp = new MockHttpServletResponse();

        rateLimiter.doFilterInternal(requestNuevaIp, responseNuevaIp, new MockFilterChain());
        assertThat(responseNuevaIp.getStatus()).isNotEqualTo(429);
    }
}
