package cl.agrotrack.audit.infraestructura.web;

import cl.agrotrack.audit.aplicacion.AuditService;
import cl.agrotrack.audit.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
@Import(SecurityConfig.class)
class AuditControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditService audit;

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/audit/deliveries/DEL-1/timeline")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    @DisplayName("El auditor lee el timeline")
    void auditorLee() throws Exception {
        when(audit.timelineDe("DEL-1")).thenReturn(List.of());
        mvc.perform(get("/api/audit/deliveries/DEL-1/timeline")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"OPERADOR"})
    @DisplayName("Operador y productor no entran a auditoria: 403")
    void otrosNo() throws Exception {
        mvc.perform(get("/api/audit/events")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void clienteNo() throws Exception {
        mvc.perform(get("/api/audit/events")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Los filtros llegan parseados al servicio")
    void filtros() throws Exception {
        mvc.perform(get("/api/audit/events")
                        .param("usuario", "op-1").param("tipo", "delivery.received")
                        .param("desde", "2026-09-01T00:00:00Z").param("limite", "50"))
                .andExpect(status().isOk());

        verify(audit).buscar(eq("op-1"), eq(Instant.parse("2026-09-01T00:00:00Z")), isNull(), eq("delivery.received"), eq(50));
    }
}
