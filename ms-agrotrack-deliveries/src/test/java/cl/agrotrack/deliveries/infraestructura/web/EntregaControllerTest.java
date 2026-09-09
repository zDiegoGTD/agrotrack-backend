package cl.agrotrack.deliveries.infraestructura.web;

import cl.agrotrack.deliveries.aplicacion.Dtos.EntregaResponse;
import cl.agrotrack.deliveries.aplicacion.EntregaService;
import cl.agrotrack.deliveries.aplicacion.Excepciones.CapacidadInsuficiente;
import cl.agrotrack.deliveries.aplicacion.Excepciones.TransicionNoPermitida;
import cl.agrotrack.deliveries.config.SecurityConfig;
import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.dominio.MotivoRechazo;
import cl.agrotrack.deliveries.dominio.Rol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EntregaController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class EntregaControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean EntregaService servicio;

    private static EntregaResponse respuesta(EstadoEntrega estado) {
        return new EntregaResponse(1L, "DEL-2026-000001", "prod-1", 7L, 3L, new BigDecimal("100"), null,
                estado, estado.etiqueta(), estado.esTerminal(), null, Instant.now(), null, null);
    }

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/deliveries")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "prod-1", roles = "CLIENTE")
    @DisplayName("El productor registra: 201 y el actor que llega al servicio es CLIENTE con su id")
    void productorRegistra() throws Exception {
        when(servicio.registrar(any(), any())).thenReturn(respuesta(EstadoEntrega.REGISTRADA));

        mvc.perform(post("/api/deliveries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":7,\"bodegaId\":3,\"cantidad\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigo").value("DEL-2026-000001"))
                .andExpect(jsonPath("$.estadoEtiqueta").value("Registrada"));

        ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
        verify(servicio).registrar(any(), actor.capture());
        assertThat(actor.getValue().userId()).isEqualTo("prod-1");
        assertThat(actor.getValue().rol()).isEqualTo(Rol.CLIENTE);
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    @DisplayName("El auditor no registra ni cambia estados: 403 sin llegar al servicio")
    void auditorSoloLee() throws Exception {
        mvc.perform(post("/api/deliveries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":7,\"bodegaId\":3,\"cantidad\":100}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/deliveries/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RECIBIDA\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("El productor tampoco cambia estados por HTTP: 403")
    void clienteNoCambiaEstado() throws Exception {
        mvc.perform(put("/api/deliveries/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RECIBIDA\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Transicion invalida: 409 problem+json con estado actual y solicitado")
    void transicionInvalida409() throws Exception {
        when(servicio.cambiarEstado(eq(1L), any(), any())).thenThrow(
                new TransicionNoPermitida(MotivoRechazo.TRANSICION_NO_VALIDA, EstadoEntrega.REGISTRADA, EstadoEntrega.EN_DESPACHO));

        mvc.perform(put("/api/deliveries/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"EN_DESPACHO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("TRANSICION_NO_VALIDA"))
                .andExpect(jsonPath("$.estadoActual").value("REGISTRADA"))
                .andExpect(jsonPath("$.estadoSolicitado").value("EN_DESPACHO"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Sin capacidad en bodega: 409")
    void sinCapacidad409() throws Exception {
        when(servicio.cambiarEstado(eq(1L), any(), any()))
                .thenThrow(new CapacidadInsuficiente("Capacidad insuficiente: disponible 10, solicitada 100"));

        mvc.perform(put("/api/deliveries/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RECIBIDA\",\"pesoRecibido\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Capacidad insuficiente: disponible 10, solicitada 100"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Filtro por estado con tilde y fechas ISO se parsea sin error")
    void filtroConTilde() throws Exception {
        mvc.perform(get("/api/deliveries")
                        .param("status", "EN_CLASIFICACIÓN")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-30T23:59:59Z"))
                .andExpect(status().isOk());

        verify(servicio).listar(eq(EstadoEntrega.EN_CLASIFICACION),
                eq(Instant.parse("2026-09-01T00:00:00Z")), eq(Instant.parse("2026-09-30T23:59:59Z")), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Estado desconocido en el filtro: 400")
    void estadoDesconocido400() throws Exception {
        mvc.perform(get("/api/deliveries").param("status", "VOLANDO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("Body invalido al registrar: 400 con campos")
    void bodyInvalido() throws Exception {
        mvc.perform(post("/api/deliveries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.productoId").exists())
                .andExpect(jsonPath("$.campos.cantidad").exists());
    }
}
