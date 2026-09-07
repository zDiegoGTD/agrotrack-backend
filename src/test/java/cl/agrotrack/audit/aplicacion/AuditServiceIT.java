package cl.agrotrack.audit.aplicacion;

import cl.agrotrack.audit.infraestructura.persistencia.EventoTimeline;
import cl.agrotrack.audit.soporte.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditServiceIT extends PostgresIT {

    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    private static String evento(String eventId, String codigo, String type, String actor, String ocurrido) {
        return """
                {"specVersion":"1.0","type":"%s","eventId":"%s","occurredAt":"%s",
                 "traceId":"t1","correlationId":"c1","source":"ms-agrotrack-deliveries","subject":"%s",
                 "actor":{"userId":"%s","nombre":"Persona %s","role":"OPERADOR"},
                 "data":{"codigo":"%s","estado":"RECIBIDA","pesoRecibido":98.5,"bodegaId":3}}
                """.formatted(type, eventId, ocurrido, codigo, actor, actor, codigo);
    }

    @Test
    @DisplayName("Un evento nuevo queda en el timeline con su payload JSONB completo")
    void registra() {
        String id = UUID.randomUUID().toString();
        var registrado = audit.registrar(evento(id, "DEL-A-1", "delivery.received", "op-1", "2026-09-07T12:00:00Z"));

        assertThat(registrado).isPresent();
        EventoTimeline e = registrado.get();
        assertThat(e.getEventId()).isEqualTo(id);
        assertThat(e.getTipo()).isEqualTo("delivery.received");
        assertThat(e.getActorNombre()).isEqualTo("Persona op-1");
        assertThat(e.getPayload()).containsEntry("estado", "RECIBIDA").containsKey("pesoRecibido");

        // Se puede consultar dentro del JSONB con SQL, sin desarmar nada
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evento_timeline WHERE payload->>'estado' = 'RECIBIDA' AND event_id = ?",
                Integer.class, id);
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("El mismo eventId dos veces: una sola fila y la segunda llamada devuelve vacio")
    void idempotente() {
        String id = UUID.randomUUID().toString();
        String cuerpo = evento(id, "DEL-A-2", "delivery.received", "op-1", "2026-09-07T12:00:00Z");

        assertThat(audit.registrar(cuerpo)).isPresent();
        assertThat(audit.registrar(cuerpo)).isEmpty();
        assertThat(audit.timelineDe("DEL-A-2")).hasSize(1);
    }

    @Test
    @DisplayName("El timeline de una entrega sale en orden cronologico")
    void timelineEnOrden() {
        audit.registrar(evento(UUID.randomUUID().toString(), "DEL-A-3", "delivery.dispatched", "op-1", "2026-09-07T15:00:00Z"));
        audit.registrar(evento(UUID.randomUUID().toString(), "DEL-A-3", "delivery.registered", "prod-1", "2026-09-07T09:00:00Z"));
        audit.registrar(evento(UUID.randomUUID().toString(), "DEL-A-3", "delivery.received", "op-1", "2026-09-07T12:00:00Z"));

        List<EventoTimeline> t = audit.timelineDe("DEL-A-3");
        assertThat(t).extracting(EventoTimeline::getTipo)
                .containsExactly("delivery.registered", "delivery.received", "delivery.dispatched");
    }

    @Test
    @DisplayName("Buscar filtra por usuario, tipo y rango de fechas")
    void filtros() {
        audit.registrar(evento(UUID.randomUUID().toString(), "DEL-A-4", "delivery.rejected", "aud-x", "2026-01-01T10:00:00Z"));
        audit.registrar(evento(UUID.randomUUID().toString(), "DEL-A-5", "delivery.received", "aud-x", "2026-01-02T10:00:00Z"));

        assertThat(audit.buscar("aud-x", null, null, null, 100)).hasSize(2);
        assertThat(audit.buscar("aud-x", null, null, "delivery.rejected", 100)).hasSize(1);
        assertThat(audit.buscar("aud-x", Instant.parse("2026-01-02T00:00:00Z"), null, null, 100))
                .extracting(EventoTimeline::getEntregaCodigo).containsExactly("DEL-A-5");
        assertThat(audit.buscar("nadie", null, null, null, 100)).isEmpty();
    }

    @Test
    @DisplayName("JSON invalido o sin eventId: MensajeInvalidoException, nada en la base")
    void invalido() {
        assertThatThrownBy(() -> audit.registrar("{no es json")).isInstanceOf(MensajeInvalidoException.class);
        assertThatThrownBy(() -> audit.registrar("{\"type\":\"x\",\"subject\":\"y\"}")).isInstanceOf(MensajeInvalidoException.class);
    }

    @Test
    @DisplayName("La base impide modificar o borrar auditoria, aunque alguien lo intente por SQL")
    void inmutable() {
        String id = UUID.randomUUID().toString();
        audit.registrar(evento(id, "DEL-A-6", "delivery.received", "op-1", "2026-09-07T12:00:00Z"));

        assertThatThrownBy(() -> jdbc.update("UPDATE evento_timeline SET tipo = 'x' WHERE event_id = ?", id))
                .hasMessageContaining("solo insercion");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM evento_timeline WHERE event_id = ?", id))
                .hasMessageContaining("solo insercion");
    }
}
