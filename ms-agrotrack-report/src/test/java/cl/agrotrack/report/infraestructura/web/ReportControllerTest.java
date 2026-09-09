package cl.agrotrack.report.infraestructura.web;

import cl.agrotrack.report.aplicacion.ReportService;
import cl.agrotrack.report.aplicacion.ReportService.Kpis;
import cl.agrotrack.report.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, ReportControllerTest.RelojFijo.class})
class ReportControllerTest {

    @TestConfiguration
    static class RelojFijo {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean ReportService report;

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/report/kpis")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Reporteria es solo del Admin: operador 403")
    void operadorNo() throws Exception {
        mvc.perform(get("/api/report/kpis")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin: 200 con el rango aplicado")
    void adminOk() throws Exception {
        Instant hasta = Instant.parse("2026-09-07T12:00:00Z");
        when(report.kpis(any())).thenReturn(new Kpis("last24h", hasta.minusSeconds(86400), hasta,
                List.of(), 42.5, 3, Map.of("REGISTRADA", 2L), 2));

        mvc.perform(get("/api/report/kpis").param("range", "last24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiempoCicloPromedioMin").value(42.5))
                .andExpect(jsonPath("$.estadosActivos.REGISTRADA").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("range invalido: 400 problem+json")
    void rangoInvalido() throws Exception {
        mvc.perform(get("/api/report/top-services").param("range", "ayer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }
}
