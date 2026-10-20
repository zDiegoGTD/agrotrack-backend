package cl.agrotrack.mqadmin.infraestructura.web;

import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException;
import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.EstadoCola;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.TipoExchange;
import cl.agrotrack.mqadmin.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La API de administracion: rutas, verbos, validacion de entrada, roles y codigos de error. */
@WebMvcTest(controllers = {AdministracionController.class, TopologiaController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class AdministracionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean RabbitAdminService servicio;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor operador() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    // ---- colas ----

    @Test
    @DisplayName("POST /queues como ADMIN crea la cola: 201 con Location, durable y con DLQ por defecto")
    void crearCola() throws Exception {
        when(servicio.crearCola("q.prueba", true, true)).thenReturn(new EstadoCola("q.prueba", 0, 0, true));

        mvc.perform(post("/api/mq/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.prueba\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/mq/queues/q.prueba")))
                .andExpect(jsonPath("$.nombre").value("q.prueba"))
                .andExpect(jsonPath("$.existe").value(true));
        verify(servicio).crearCola("q.prueba", true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"nombre\":\"\"}", "{\"nombre\":\"   \"}", "{}",
            "{\"nombre\":\"con espacios\"}", "{\"nombre\":\"amq.reservado\"}", "{\"nombre\":\"cola/rara\"}"})
    @DisplayName("Nombres vacios, con espacios, reservados o con caracteres raros: 400 sin tocar RabbitMQ")
    void nombreInvalido(String cuerpo) throws Exception {
        mvc.perform(post("/api/mq/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.nombre").exists());
        verify(servicio, never()).crearCola(anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Un nombre de mas de 200 caracteres se rechaza")
    void nombreLargo() throws Exception {
        String largo = "q" + "x".repeat(200);
        mvc.perform(post("/api/mq/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"" + largo + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /queues/{nombre} responde 204")
    void eliminarCola() throws Exception {
        mvc.perform(delete("/api/mq/queues/q.vieja").with(admin()))
                .andExpect(status().isNoContent());
        verify(servicio).eliminarCola("q.vieja");
    }

    @Test
    @DisplayName("DELETE de una cola del sistema: 403 con codigo RECURSO_DEL_SISTEMA")
    void eliminarColaDelSistema() throws Exception {
        doThrow(new AdministracionInvalidaException(Motivo.RECURSO_DEL_SISTEMA, "q.cmd.email es del sistema"))
                .when(servicio).eliminarCola("q.cmd.email");

        mvc.perform(delete("/api/mq/queues/q.cmd.email").with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("RECURSO_DEL_SISTEMA"));
    }

    @Test
    @DisplayName("DELETE de una cola inexistente: 404 con codigo NO_EXISTE")
    void eliminarColaInexistente() throws Exception {
        doThrow(new AdministracionInvalidaException(Motivo.NO_EXISTE, "no existe"))
                .when(servicio).eliminarCola("q.fantasma");

        mvc.perform(delete("/api/mq/queues/q.fantasma").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_EXISTE"));
    }

    @Test
    @DisplayName("Crear una cola que ya existe con otros atributos: 409")
    void conflicto() throws Exception {
        when(servicio.crearCola(anyString(), anyBoolean(), anyBoolean()))
                .thenThrow(new AdministracionInvalidaException(Motivo.CONFLICTO_CON_EXISTENTE, "inequivalent arg"));

        mvc.perform(post("/api/mq/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"q.choca\",\"durable\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONFLICTO_CON_EXISTENTE"));
    }

    // ---- exchanges ----

    @Test
    @DisplayName("POST /exchanges con tipo TOPIC: 201")
    void crearExchange() throws Exception {
        mvc.perform(post("/api/mq/exchanges").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"ex.pruebas\",\"tipo\":\"TOPIC\"}"))
                .andExpect(status().isCreated());
        verify(servicio).crearExchange("ex.pruebas", TipoExchange.TOPIC, true);
    }

    @Test
    @DisplayName("Exchange sin tipo: 400 indicando el campo")
    void exchangeSinTipo() throws Exception {
        mvc.perform(post("/api/mq/exchanges").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"ex.pruebas\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.tipo").exists());
        verify(servicio, never()).crearExchange(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Exchange de tipo no admitido (headers): 400 con mensaje claro")
    void exchangeTipoInvalido() throws Exception {
        mvc.perform(post("/api/mq/exchanges").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"ex.pruebas\",\"tipo\":\"HEADERS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    @DisplayName("DELETE /exchanges/{nombre}: 204")
    void eliminarExchange() throws Exception {
        mvc.perform(delete("/api/mq/exchanges/ex.pruebas").with(admin()))
                .andExpect(status().isNoContent());
        verify(servicio).eliminarExchange("ex.pruebas");
    }

    // ---- bindings ----

    @Test
    @DisplayName("POST /bindings con routing key comodin de topic: 201")
    void crearBinding() throws Exception {
        mvc.perform(post("/api/mq/bindings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cola\":\"q.prueba\",\"exchange\":\"ex.pruebas\",\"routingKey\":\"pruebas.#\"}"))
                .andExpect(status().isCreated());
        verify(servicio).crearBinding("q.prueba", "ex.pruebas", "pruebas.#");
    }

    @Test
    @DisplayName("Binding sin routing key: 400")
    void bindingIncompleto() throws Exception {
        mvc.perform(post("/api/mq/bindings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cola\":\"q.prueba\",\"exchange\":\"ex.pruebas\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.routingKey").exists());
    }

    @Test
    @DisplayName("DELETE /bindings con el mismo cuerpo: 204")
    void eliminarBinding() throws Exception {
        mvc.perform(delete("/api/mq/bindings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cola\":\"q.prueba\",\"exchange\":\"ex.pruebas\",\"routingKey\":\"pruebas.#\"}"))
                .andExpect(status().isNoContent());
        verify(servicio).eliminarBinding("q.prueba", "ex.pruebas", "pruebas.#");
    }

    // ---- seguridad ----

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(post("/api/mq/queues").contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"q.x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("OPERADOR puede ver la topologia pero no administrar: 403")
    void operadorNoAdministra() throws Exception {
        mvc.perform(post("/api/mq/queues").with(operador())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"q.x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/mq/queues/q.x").with(operador()))
                .andExpect(status().isForbidden());
        verify(servicio, never()).crearCola(anyString(), anyBoolean(), anyBoolean());
        verify(servicio, never()).eliminarCola(anyString());
    }
}
