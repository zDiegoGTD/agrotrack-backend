package cl.agrotrack.kafkaadmin.infraestructura.web;

import cl.agrotrack.kafkaadmin.aplicacion.AdministracionInvalidaException;
import cl.agrotrack.kafkaadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoGrupo;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoTopico;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.Posicion;
import cl.agrotrack.kafkaadmin.config.SecurityConfig;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La API de administracion de Kafka: rutas, verbos, validacion, roles y codigos de error. */
@WebMvcTest(controllers = {AdministracionController.class, TopicosController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class AdministracionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean KafkaAdminService servicio;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor operador() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private static EstadoTopico topico(String nombre, int particiones) {
        return new EstadoTopico(nombre, particiones, 1, Map.of("retention.ms", "3600000"), false);
    }

    // ---- topicos ----

    @Test
    @DisplayName("POST /topics como ADMIN crea el topico: 201 con Location y la config pedida")
    void crearTopico() throws Exception {
        when(servicio.crearTopico("demo.kafka", 3, (short) 1, Map.of("retention.ms", "3600000")))
                .thenReturn(topico("demo.kafka", 3));

        mvc.perform(post("/api/kafka/topics").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"demo.kafka\",\"particiones\":3,\"replicas\":1,"
                                + "\"configuracion\":{\"retention.ms\":\"3600000\"}}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/kafka/topics/demo.kafka")))
                .andExpect(jsonPath("$.nombre").value("demo.kafka"))
                .andExpect(jsonPath("$.particiones").value(3))
                .andExpect(jsonPath("$.configuracion['retention.ms']").value("3600000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"nombre\":\"\",\"particiones\":3,\"replicas\":1}",
            "{\"nombre\":\"con espacios\",\"particiones\":3,\"replicas\":1}",
            "{\"nombre\":\"__consumer_offsets\",\"particiones\":3,\"replicas\":1}",
            "{\"nombre\":\"topico/raro\",\"particiones\":3,\"replicas\":1}",
            "{\"nombre\":\"demo\",\"particiones\":0,\"replicas\":1}",
            "{\"nombre\":\"demo\",\"particiones\":3,\"replicas\":4}"})
    @DisplayName("Nombre vacio, con espacios, interno (__), particiones 0 o replicas > 3: 400 sin tocar Kafka")
    void topicoInvalido(String cuerpo) throws Exception {
        mvc.perform(post("/api/kafka/topics").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
        verifyNoInteractions(servicio);
    }

    @Test
    @DisplayName("Crear como OPERADOR: 403 (solo ADMIN escribe)")
    void crearComoOperador() throws Exception {
        mvc.perform(post("/api/kafka/topics").with(operador())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"demo\",\"particiones\":3,\"replicas\":1}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(servicio);
    }

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/kafka/topics")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /topics como OPERADOR lista todos los topicos")
    void listarComoOperador() throws Exception {
        when(servicio.topicos()).thenReturn(List.of(topico("deliveries.events", 3), topico("demo", 1)));

        mvc.perform(get("/api/kafka/topics").with(operador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("deliveries.events"));
    }

    @Test
    @DisplayName("DELETE /topics/{nombre}: 204")
    void eliminarTopico() throws Exception {
        mvc.perform(delete("/api/kafka/topics/demo.kafka").with(admin()))
                .andExpect(status().isNoContent());
        verify(servicio).eliminarTopico("demo.kafka");
    }

    @Test
    @DisplayName("DELETE de un topico del sistema: 403 RECURSO_DEL_SISTEMA")
    void eliminarTopicoDelSistema() throws Exception {
        doThrow(new AdministracionInvalidaException(Motivo.RECURSO_DEL_SISTEMA, "deliveries.events es del sistema"))
                .when(servicio).eliminarTopico("deliveries.events");

        mvc.perform(delete("/api/kafka/topics/deliveries.events").with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("RECURSO_DEL_SISTEMA"));
    }

    @Test
    @DisplayName("DELETE de un topico inexistente: 404 NO_EXISTE")
    void eliminarInexistente() throws Exception {
        doThrow(new AdministracionInvalidaException(Motivo.NO_EXISTE, "no existe"))
                .when(servicio).eliminarTopico("fantasma");

        mvc.perform(delete("/api/kafka/topics/fantasma").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_EXISTE"));
    }

    @Test
    @DisplayName("PATCH /topics/{nombre}/config cambia la configuracion: 200 con el estado nuevo")
    void cambiarConfiguracion() throws Exception {
        when(servicio.cambiarConfiguracion("demo", Map.of("retention.ms", "7200000")))
                .thenReturn(new EstadoTopico("demo", 3, 1, Map.of("retention.ms", "7200000"), false));

        mvc.perform(patch("/api/kafka/topics/demo/config").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuracion\":{\"retention.ms\":\"7200000\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuracion['retention.ms']").value("7200000"));
    }

    @Test
    @DisplayName("PATCH sin configuracion: 400")
    void cambiarConfiguracionVacia() throws Exception {
        mvc.perform(patch("/api/kafka/topics/demo/config").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"configuracion\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    @DisplayName("Reducir particiones: 400 DATOS_INVALIDOS (Kafka no permite reducir)")
    void reducirParticiones() throws Exception {
        when(servicio.aumentarParticiones("demo", 1))
                .thenThrow(new AdministracionInvalidaException(Motivo.DATOS_INVALIDOS, "tiene 3, no se puede bajar a 1"));

        mvc.perform(post("/api/kafka/topics/demo/particiones").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"particiones\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    @DisplayName("Aumentar particiones: 200 con el total nuevo")
    void aumentarParticiones() throws Exception {
        when(servicio.aumentarParticiones("demo", 4)).thenReturn(topico("demo", 4));

        mvc.perform(post("/api/kafka/topics/demo/particiones").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"particiones\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particiones").value(4));
    }

    @Test
    @DisplayName("Replicas mayores que brokers: el broker rechaza y responde 409")
    void replicasDeMas() throws Exception {
        when(servicio.crearTopico(anyString(), anyInt(), anyShort(), any()))
                .thenThrow(new AdministracionInvalidaException(Motivo.CONFLICTO_CON_EXISTENTE, "replication factor 3 > 1 broker"));

        mvc.perform(post("/api/kafka/topics").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"demo\",\"particiones\":3,\"replicas\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONFLICTO_CON_EXISTENTE"));
    }

    // ---- consumer groups ----

    @Test
    @DisplayName("GET /consumer-groups como OPERADOR: estado, miembros y lag")
    void grupos() throws Exception {
        when(servicio.grupos()).thenReturn(List.of(
                new EstadoGrupo("audit-consumer", "STABLE", 1, Map.of("deliveries.events", 0L), 0L)));

        mvc.perform(get("/api/kafka/consumer-groups").with(operador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grupo").value("audit-consumer"))
                .andExpect(jsonPath("$[0].lagTotal").value(0));
    }

    @Test
    @DisplayName("Reiniciar offsets al inicio: 200")
    void reiniciarOffsets() throws Exception {
        when(servicio.reiniciarOffsets("demo-grupo", "demo", Posicion.INICIO))
                .thenReturn(new EstadoGrupo("demo-grupo", "EMPTY", 0, Map.of("demo", 5L), 5L));

        mvc.perform(post("/api/kafka/consumer-groups/demo-grupo/topics/demo/offsets").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posicion\":\"INICIO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lagTotal").value(5));
    }

    @Test
    @DisplayName("Reiniciar offsets de un grupo con miembros activos: 409")
    void reiniciarGrupoActivo() throws Exception {
        when(servicio.reiniciarOffsets("audit-consumer", "deliveries.events", Posicion.FIN))
                .thenThrow(new AdministracionInvalidaException(Motivo.CONFLICTO_CON_EXISTENTE, "el grupo tiene miembros activos"));

        mvc.perform(post("/api/kafka/consumer-groups/audit-consumer/topics/deliveries.events/offsets").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posicion\":\"FIN\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Posicion no admitida: 400")
    void posicionInvalida() throws Exception {
        mvc.perform(post("/api/kafka/consumer-groups/g/topics/t/offsets").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posicion\":\"MEDIO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }
}
