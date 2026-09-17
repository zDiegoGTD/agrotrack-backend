package cl.agrotrack.bff.cuenta;

import cl.agrotrack.bff.config.JwtRolesConverter;
import cl.agrotrack.bff.config.SecurityConfig;
import cl.agrotrack.bff.infraestructura.web.MeController;
import cl.agrotrack.bff.infraestructura.web.ProxyController;
import cl.agrotrack.bff.infraestructura.web.Reenviador;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Segunda capa de autorizacion: el token trae el rol Y la cuenta esta
 * aprobada. La matriz de roles va primero; este filtro, despues.
 */
@WebMvcTest({ProxyController.class, MeController.class, ProxyController.Servicios.class})
@Import(SecurityConfig.class)
class BffCuentaTest {

    @Autowired MockMvc mvc;
    @MockitoBean Reenviador reenviador;
    @MockitoBean EstadoCuentas cuentas;

    @BeforeEach
    void preparar() {
        when(reenviador.reenviar(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}".getBytes()));
    }

    private static JwtRequestPostProcessor con(String... roles) {
        Jwt base = Jwt.withTokenValue("t").header("alg", "none").claim("roles", List.of(roles)).claim("oid", "u-1").build();
        return jwt().jwt(j -> j.claim("roles", List.of(roles)).claim("oid", "u-1").claim("name", "Diego")
                        .claim("preferred_username", "diego@agrotrack.local"))
                .authorities(new JwtRolesConverter().convert(base).getAuthorities());
    }

    private void cuentaEn(String estado) {
        CuentaUsuario c = new CuentaUsuario(9L, estado, null, Instant.parse("2026-09-15T10:00:00Z"));
        when(cuentas.consultar(eq("u-1"), any())).thenReturn(c);
        when(cuentas.sincronizar(eq("u-1"), any())).thenReturn(c);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"PENDIENTE,CUENTA_PENDIENTE", "RECHAZADO,CUENTA_RECHAZADA", "INACTIVO,CUENTA_INACTIVA"})
    @DisplayName("Cuenta no activa: 403 con su codigo y nada se reenvia")
    void noActivaBloqueada(String estado, String codigo) throws Exception {
        cuentaEn(estado);

        mvc.perform(get("/api/deliveries").with(con("CLIENTE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(codigo))
                .andExpect(jsonPath("$.estado").value(estado));
        verify(reenviador, never()).reenviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Un PENDIENTE si puede pedir /api/me, que ademas lo registra")
    void pendienteVeSuEstado() throws Exception {
        cuentaEn("PENDIENTE");

        mvc.perform(get("/api/me").with(con("CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.usuarioId").value(9))
                .andExpect(jsonPath("$.primerIngreso").value("2026-09-15T10:00:00Z"));
        verify(cuentas).sincronizar(eq("u-1"), any());
        verify(cuentas, never()).consultar(any(), any());
    }

    @Test
    @DisplayName("Cuenta ACTIVA: pasa y se reenvia")
    void activaPasa() throws Exception {
        cuentaEn("ACTIVO");

        mvc.perform(get("/api/deliveries").with(con("CLIENTE"))).andExpect(status().isOk());
        verify(reenviador).reenviar(eq(HttpMethod.GET), any(), any(), any());
    }

    @Test
    @DisplayName("users caido: 503 en el filtro, nunca se deja pasar")
    void usersCaidoFiltro() throws Exception {
        when(cuentas.consultar(any(), any())).thenThrow(new UsuariosNoDisponibleException("caido", null));

        mvc.perform(get("/api/deliveries").with(con("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("USUARIOS_NO_DISPONIBLE"));
        verify(reenviador, never()).reenviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("users caido: 503 tambien en /api/me")
    void usersCaidoMe() throws Exception {
        when(cuentas.sincronizar(any(), any())).thenThrow(new UsuariosNoDisponibleException("caido", null));

        mvc.perform(get("/api/me").with(con("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("USUARIOS_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("La matriz va primero: un CLIENTE en /api/users recibe 403 sin consultar su cuenta")
    void matrizAntesQueCuenta() throws Exception {
        mvc.perform(get("/api/users").with(con("CLIENTE"))).andExpect(status().isForbidden());
        verify(cuentas, never()).consultar(any(), any());
    }

    @Test
    @DisplayName("ADMIN activo lista usuarios: se reenvia a users con la query")
    void adminListaUsuarios() throws Exception {
        cuentaEn("ACTIVO");

        mvc.perform(get("/api/users").param("estado", "PENDIENTE").with(con("ADMIN"))).andExpect(status().isOk());

        ArgumentCaptor<URI> destino = ArgumentCaptor.forClass(URI.class);
        verify(reenviador).reenviar(eq(HttpMethod.GET), destino.capture(), any(), any());
        assertThat(destino.getValue().toString()).isEqualTo("http://localhost:8089/api/users?estado=PENDIENTE");
    }

    @Test
    @DisplayName("El productor activo guarda su ficha; el auditor no")
    void fichaPorRol() throws Exception {
        cuentaEn("ACTIVO");
        String ficha = "{\"rut\":\"12345678-9\",\"razonSocial\":\"A\"}";

        mvc.perform(put("/api/users/9/perfil").with(con("CLIENTE")).contentType(MediaType.APPLICATION_JSON).content(ficha))
                .andExpect(status().isOk());
        mvc.perform(put("/api/users/9/perfil").with(con("AUDITOR")).contentType(MediaType.APPLICATION_JSON).content(ficha))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Solo el ADMIN cambia estados de cuentas")
    void estadoSoloAdmin() throws Exception {
        cuentaEn("ACTIVO");
        String body = "{\"estado\":\"ACTIVO\"}";

        mvc.perform(put("/api/users/5/estado").with(con("OPERADOR")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/5/estado").with(con("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sincronizar no se expone hacia fuera, ni para ADMIN")
    void sincronizarNoExpuesto() throws Exception {
        mvc.perform(post("/api/users/sincronizar").with(con("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cualquier rol activo consulta su propia cuenta")
    void usersMe() throws Exception {
        cuentaEn("ACTIVO");
        mvc.perform(get("/api/users/me").with(con("AUDITOR"))).andExpect(status().isOk());
    }
}
