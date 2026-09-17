package cl.agrotrack.users.infraestructura.web;

import cl.agrotrack.users.aplicacion.Dtos.PerfilResponse;
import cl.agrotrack.users.aplicacion.Dtos.UsuarioResponse;
import cl.agrotrack.users.aplicacion.IdentidadToken;
import cl.agrotrack.users.aplicacion.RutDuplicadoException;
import cl.agrotrack.users.aplicacion.UsuarioService;
import cl.agrotrack.users.config.SecurityConfig;
import cl.agrotrack.users.dominio.CambioEstadoInvalidoException;
import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.dominio.MotivoRechazoCambio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Autorizacion por rol, validacion y forma de los errores, sin base de datos. */
@WebMvcTest(UsuarioController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class UsuarioControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean UsuarioService servicio;

    private static JwtRequestPostProcessor con(String oid, String... roles) {
        return jwt().jwt(j -> j.claim("oid", oid).claim("name", "N " + oid)
                        .claim("preferred_username", oid + "@agrotrack.cl").claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles).<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static UsuarioResponse usuario(long id, EstadoUsuario estado) {
        return new UsuarioResponse(id, "oid-" + id, "u@agrotrack.cl", "U", estado, "CLIENTE", null,
                Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T10:00:00Z"), null, null, null);
    }

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sincronizar usa los claims del token")
    void sincronizar() throws Exception {
        when(servicio.sincronizar(any())).thenReturn(usuario(1, EstadoUsuario.PENDIENTE));

        mvc.perform(post("/api/users/sincronizar").with(con("oid-1", "CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        ArgumentCaptor<IdentidadToken> id = ArgumentCaptor.forClass(IdentidadToken.class);
        verify(servicio).sincronizar(id.capture());
        assertThat(id.getValue().oid()).isEqualTo("oid-1");
        assertThat(id.getValue().email()).isEqualTo("oid-1@agrotrack.cl");
        assertThat(id.getValue().roles()).containsExactly("CLIENTE");
    }

    @Test
    @DisplayName("Un productor no lista usuarios: 403")
    void clienteNoLista() throws Exception {
        mvc.perform(get("/api/users").with(con("c", "CLIENTE"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("El admin lista filtrando por estado")
    void adminLista() throws Exception {
        when(servicio.listar(EstadoUsuario.PENDIENTE)).thenReturn(List.of(usuario(2, EstadoUsuario.PENDIENTE)));

        mvc.perform(get("/api/users").param("estado", "PENDIENTE").with(con("a", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2));
    }

    @Test
    @DisplayName("Estado desconocido en el filtro: 400, no 500")
    void filtroInvalido() throws Exception {
        mvc.perform(get("/api/users").param("estado", "BORRADO").with(con("a", "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("El jefe de acopio no aprueba cuentas: 403")
    void operadorNoAprueba() throws Exception {
        mvc.perform(put("/api/users/2/estado").with(con("o", "OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cambiar estado pasa el oid del admin que lo hace")
    void adminAprueba() throws Exception {
        when(servicio.cambiarEstado(eq(2L), any(), eq("admin-oid"))).thenReturn(usuario(2, EstadoUsuario.ACTIVO));

        mvc.perform(put("/api/users/2/estado").with(con("admin-oid", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }

    @Test
    @DisplayName("Auto-desactivacion: 409 con codigo")
    void autoDesactivacion() throws Exception {
        when(servicio.cambiarEstado(eq(1L), any(), any())).thenThrow(
                new CambioEstadoInvalidoException(MotivoRechazoCambio.AUTO_DESACTIVACION, "No puedes desactivar tu propia cuenta"));

        mvc.perform(put("/api/users/1/estado").with(con("a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"INACTIVO\",\"motivo\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("AUTO_DESACTIVACION"))
                .andExpect(jsonPath("$.detail").value("No puedes desactivar tu propia cuenta"));
    }

    @Test
    @DisplayName("Rechazar sin motivo: 400 con codigo")
    void motivoObligatorio() throws Exception {
        when(servicio.cambiarEstado(eq(2L), any(), any())).thenThrow(
                new CambioEstadoInvalidoException(MotivoRechazoCambio.MOTIVO_OBLIGATORIO, "Indica el motivo"));

        mvc.perform(put("/api/users/2/estado").with(con("a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"RECHAZADO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("MOTIVO_OBLIGATORIO"));
    }

    @Test
    @DisplayName("Ficha con RUT mal escrito: 400 con el campo")
    void rutInvalido() throws Exception {
        mvc.perform(put("/api/users/2/perfil").with(con("c", "CLIENTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12.345.678-9\",\"razonSocial\":\"A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.rut").exists());
    }

    @Test
    @DisplayName("Auditor no edita fichas: 403 antes de llegar al servicio")
    void auditorNoEditaFicha() throws Exception {
        mvc.perform(put("/api/users/2/perfil").with(con("x", "AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12345678-9\",\"razonSocial\":\"A\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RUT duplicado: 409 con codigo")
    void rutDuplicado() throws Exception {
        when(servicio.actualizarPerfil(eq(2L), any(), any())).thenThrow(new RutDuplicadoException("12345678-9"));

        mvc.perform(put("/api/users/2/perfil").with(con("a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12345678-9\",\"razonSocial\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RUT_DUPLICADO"));
    }

    @Test
    @DisplayName("El productor guarda su ficha")
    void productorGuarda() throws Exception {
        when(servicio.actualizarPerfil(eq(2L), any(), any()))
                .thenReturn(new PerfilResponse("12345678-9", "Agricola Sur", null, null, null));

        mvc.perform(put("/api/users/2/perfil").with(con("c", "CLIENTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12345678-9\",\"razonSocial\":\"Agricola Sur\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial").value("Agricola Sur"));
    }

    @Test
    @DisplayName("GET /api/users/me no se confunde con /api/users/{id}")
    void meNoEsId() throws Exception {
        when(servicio.me("c")).thenReturn(usuario(7, EstadoUsuario.ACTIVO));

        mvc.perform(get("/api/users/me").with(con("c", "CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        verify(servicio).me("c");
    }

    @Test
    @DisplayName("Listar sin filtro pasa null")
    void listarSinFiltro() throws Exception {
        when(servicio.listar(isNull())).thenReturn(List.of());
        mvc.perform(get("/api/users").with(con("a", "ADMIN"))).andExpect(status().isOk());
    }
}
