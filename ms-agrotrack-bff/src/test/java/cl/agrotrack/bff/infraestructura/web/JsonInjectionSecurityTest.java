package cl.agrotrack.bff.infraestructura.web;

import cl.agrotrack.bff.config.SecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de seguridad para validar la mitigación de inyección JSON y malformación
 * de respuestas RFC 7807 (ProblemDetail) en ProxyController y ReenviadorHttp.
 */
@WebMvcTest({ProxyController.class, MeController.class, ProxyController.Servicios.class})
@Import(SecurityConfig.class)
class JsonInjectionSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private Reenviador reenviador;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Unitario 1: ProxyController genera JSON válido ante caracteres maliciosos e inyección en el detalle")
    void proxyControllerSanitizaDetalleInyectado() throws Exception {
        ProxyController.Servicios servicios = new ProxyController.Servicios();
        ProxyController controller = new ProxyController(servicios, reenviador, objectMapper);

        // Simulamos petición a servicio no mapeado con caracteres de inyección JSON: comillas, llaves y saltos de línea
        String payloadMalicioso = "malicioso\",\"injected\":true,\"hack\":\"yes\n\r\t";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/" + payloadMalicioso + "/test");

        ResponseEntity<byte[]> response = controller.proxy(request, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");

        String json = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThatCode(() -> {
            JsonNode node = objectMapper.readTree(json);
            assertThat(node.has("status")).isTrue();
            assertThat(node.get("status").asInt()).isEqualTo(404);
            assertThat(node.has("detail")).isTrue();
            assertThat(node.get("detail").asText()).contains(payloadMalicioso);
            assertThat(node.has("injected")).isFalse(); // La inyección como campo no tuvo efecto
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Unitario 2: ReenviadorHttp serializa ProblemDetail como JSON válido ante errores de conexión downstream")
    void reenviadorHttpSerializaProblemDetailSinConcatenacion() throws Exception {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class);
        when(builder.build()).thenReturn(restClient);
        when(restClient.method(any())).thenThrow(new ResourceAccessException("Conexión rechazada: {\"fake\":\"json\"}\",\"status\":200"));

        ReenviadorHttp reenviadorHttp = new ReenviadorHttp(builder, objectMapper);
        URI destino = URI.create("http://localhost:8082/api/deliveries");
        ResponseEntity<byte[]> response = reenviadorHttp.reenviar(HttpMethod.GET, destino, new HttpHeaders(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        String json = new String(response.getBody(), StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("status").asInt()).isEqualTo(503);
        assertThat(node.get("title").asText()).isEqualTo("Service Unavailable");
        assertThat(node.get("detail").asText()).contains("localhost:8082 no responde");
    }

    @Test
    @DisplayName("Unitario 3: Respuesta de error cumple estrictamente con estándar RFC 7807 (ProblemDetail)")
    void respuestaCumpleRfc7807() throws Exception {
        ProxyController.Servicios servicios = new ProxyController.Servicios();
        ProxyController controller = new ProxyController(servicios, reenviador, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/servicio_desconocido/test");
        ResponseEntity<byte[]> response = controller.proxy(request, null, null);

        JsonNode node = objectMapper.readTree(response.getBody());
        assertThat(node.has("type")).isTrue();
        assertThat(node.has("title")).isTrue();
        assertThat(node.has("status")).isTrue();
        assertThat(node.has("detail")).isTrue();
        assertThat(node.get("status").asInt()).isEqualTo(404);
        assertThat(node.get("title").asText()).isEqualTo("Not Found");
    }

    @Test
    @DisplayName("Integración 4: MockMvc con JWT hacia deliveries con query param malicioso es procesado y responde correctamente")
    void integracionMockMvcProblemJsonValido() throws Exception {
        when(reenviador.reenviar(any(), any(), any(), any())).thenReturn(
                ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
        );

        mvc.perform(get("/api/deliveries")
                        .param("filtro", "{\"malicious\":true,\"inject\":\"quote'\\\"\"}")
                        .with(jwt().jwt(j -> j.claim("roles", List.of("ADMIN")).claim("oid", "u-1"))
                                .authorities(new cl.agrotrack.bff.config.JwtRolesConverter()
                                        .convert(Jwt.withTokenValue("t").header("alg", "none")
                                                .claim("roles", List.of("ADMIN")).claim("oid", "u-1").build()).getAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
    }
}
