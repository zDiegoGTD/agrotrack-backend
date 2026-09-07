package cl.agrotrack.bff.infraestructura.web;

import cl.agrotrack.bff.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La matriz de autorizacion del BFF y el reenvio. Se usa el post-processor
 * jwt() para que el token real pase por JwtRolesConverter (claim roles),
 * igual que en produccion.
 */
@WebMvcTest({ProxyController.class, MeController.class, ProxyController.Servicios.class})
@Import(SecurityConfig.class)
class BffSeguridadTest {

    @Autowired MockMvc mvc;
    @MockitoBean Reenviador reenviador;

    @BeforeEach
    void reenviadorResponde() {
        when(reenviador.reenviar(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}".getBytes()));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor con(String... roles) {
        return jwt().jwt(j -> j.claim("roles", java.util.List.of(roles)).claim("oid", "u-1").claim("name", "Diego")
                .claim("preferred_username", "diego@agrotrack.local")).authorities(new cl.agrotrack.bff.config.JwtRolesConverter()
                .convert(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
                        .claim("roles", java.util.List.of(roles)).claim("oid", "u-1").build()).getAuthorities());
    }

    @Test
    @DisplayName("Sin token: 401 y nada se reenvia")
    void sinToken() throws Exception {
        mvc.perform(get("/api/deliveries")).andExpect(status().isUnauthorized());
        verify(reenviador, never()).reenviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CLIENTE a /api/report: 403 sin llegar al proxy")
    void clienteNoVeReportes() throws Exception {
        mvc.perform(get("/api/report/kpis").with(con("CLIENTE"))).andExpect(status().isForbidden());
        verify(reenviador, never()).reenviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AUDITOR no cambia estados: 403")
    void auditorNoEscribe() throws Exception {
        mvc.perform(put("/api/deliveries/1/status").with(con("AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RECIBIDA\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPERADOR no escribe en el catalogo pero si reserva capacidad")
    void operadorCatalogo() throws Exception {
        mvc.perform(post("/api/catalog/productos").with(con("OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/catalog/bodegas/3/capacidad/reservar").with(con("OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":1,\"entregaCodigo\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ruta fuera de la matriz: denegada incluso para ADMIN")
    void fueraDeMatriz() throws Exception {
        mvc.perform(get("/api/loquesea/1").with(con("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPERADOR lista entregas: se reenvia a deliveries con metodo, ruta, query y Bearer")
    void reenvioCompleto() throws Exception {
        mvc.perform(get("/api/deliveries").param("status", "RECIBIDA").with(con("OPERADOR"))
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));

        ArgumentCaptor<URI> destino = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpHeaders> cabeceras = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(reenviador).reenviar(org.mockito.ArgumentMatchers.eq(HttpMethod.GET), destino.capture(), cabeceras.capture(), any());
        assertThat(destino.getValue().toString()).isEqualTo("http://localhost:8082/api/deliveries?status=RECIBIDA");
        assertThat(cabeceras.getValue().getFirst(HttpHeaders.AUTHORIZATION)).startsWith("Bearer ");
        assertThat(cabeceras.getValue().getFirst("X-Forwarded-By")).isEqualTo("ms-agrotrack-bff");
    }

    @Test
    @DisplayName("El cuerpo y el Content-Type de un POST llegan al servicio")
    void reenviaCuerpo() throws Exception {
        mvc.perform(post("/api/deliveries").with(con("CLIENTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productoId\":1}"))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> cuerpo = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<HttpHeaders> cabeceras = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(reenviador).reenviar(org.mockito.ArgumentMatchers.eq(HttpMethod.POST), any(), cabeceras.capture(), cuerpo.capture());
        assertThat(new String(cuerpo.getValue(), StandardCharsets.UTF_8)).isEqualTo("{\"productoId\":1}");
        assertThat(cabeceras.getValue().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
    }

    @Test
    @DisplayName("Un 409 del servicio de dominio se devuelve tal cual")
    void errorDelServicioSePropaga() throws Exception {
        when(reenviador.reenviar(any(), any(), any(), any())).thenReturn(ResponseEntity.status(409)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"detail\":\"sin capacidad\"}".getBytes()));

        mvc.perform(put("/api/deliveries/1/status").with(con("OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RECIBIDA\"}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").value("sin capacidad"));
    }

    @Test
    @DisplayName("/api/me devuelve identidad y roles del token")
    void me() throws Exception {
        mvc.perform(get("/api/me").with(con("ADMIN", "OPERADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-1"))
                .andExpect(jsonPath("$.nombre").value("Diego"))
                .andExpect(jsonPath("$.email").value("diego@agrotrack.local"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.roles[1]").value("OPERADOR"));
    }
}
