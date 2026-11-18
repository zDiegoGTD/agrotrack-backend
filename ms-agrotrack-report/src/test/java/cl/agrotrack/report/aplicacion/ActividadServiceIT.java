package cl.agrotrack.report.aplicacion;

import cl.agrotrack.report.aplicacion.ActividadService.ActividadRol;
import cl.agrotrack.report.soporte.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lo que llega por audit.timeline: actividad por rol y ultimo estado de cada entrega. */
class ActividadServiceIT extends PostgresIT {

    @Autowired ActividadService actividad;
    @Autowired JdbcTemplate jdbc;

    private static ResumenTimeline resumen(String codigo, String evento, String estado, String rol, Instant cuando) {
        return new ResumenTimeline(codigo, evento, estado, "u-" + rol, rol, cuando, UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("Suma un evento por rol y hora, y el rango los totaliza por rol")
    void sumaPorRolYHora() {
        Instant h = Instant.now().minusSeconds(3600);
        actividad.aplicar(resumen("DEL-AC-1", "delivery.received", "RECIBIDA", "OPERADOR", h));
        actividad.aplicar(resumen("DEL-AC-2", "delivery.received", "RECIBIDA", "OPERADOR", h.plusSeconds(60)));
        actividad.aplicar(resumen("DEL-AC-1", "delivery.rejected", "RECHAZADA", "SUPERVISOR", h.plusSeconds(120)));

        var porRol = actividad.actividad(Rango.parse("last24h", Clock.systemUTC()));

        assertThat(porRol).contains(new ActividadRol("OPERADOR", 2), new ActividadRol("SUPERVISOR", 1));
    }

    @Test
    @DisplayName("El mismo resumen dos veces (reentrega) suma una sola vez")
    void duplicadoNoSumaDosVeces() {
        ResumenTimeline r = resumen("DEL-AC-3", "delivery.registered", "REGISTRADA", "PRODUCTOR", Instant.parse("2026-01-05T10:15:00Z"));

        assertThat(actividad.aplicar(r)).isTrue();
        assertThat(actividad.aplicar(r)).isFalse();
        Integer eventos = jdbc.queryForObject(
                "SELECT eventos FROM actividad_rol WHERE hora = '2026-01-05T10:00:00Z' AND rol = 'PRODUCTOR'", Integer.class);
        assertThat(eventos).isEqualTo(1);
    }

    @Test
    @DisplayName("Ultimo estado por entrega: un resumen mas antiguo que llega tarde no lo retrocede")
    void ultimoEstadoNoRetrocede() {
        Instant t = Instant.parse("2026-01-06T10:00:00Z");
        actividad.aplicar(resumen("DEL-AC-4", "delivery.dispatched", "DESPACHADA", "OPERADOR", t.plusSeconds(600)));
        actividad.aplicar(resumen("DEL-AC-4", "delivery.received", "RECIBIDA", "OPERADOR", t));

        String estado = jdbc.queryForObject(
                "SELECT estado FROM ultimo_estado WHERE entrega_codigo = 'DEL-AC-4'", String.class);
        assertThat(estado).isEqualTo("DESPACHADA");
    }

    @Test
    @DisplayName("Resumen sin codigo, sin eventId o sin fecha: MensajeInvalido (va a la DLT sin reintentos)")
    void resumenIncompleto() {
        Instant t = Instant.now();
        assertThatThrownBy(() -> actividad.aplicar(new ResumenTimeline(null, "x", "y", "u", "OPERADOR", t, "e1")))
                .isInstanceOf(MensajeInvalidoException.class);
        assertThatThrownBy(() -> actividad.aplicar(new ResumenTimeline("DEL", "x", "y", "u", "OPERADOR", t, null)))
                .isInstanceOf(MensajeInvalidoException.class);
        assertThatThrownBy(() -> actividad.aplicar(new ResumenTimeline("DEL", "x", "y", "u", "OPERADOR", null, "e2")))
                .isInstanceOf(MensajeInvalidoException.class);
    }

    @Test
    @DisplayName("Sin rol (actor de sistema): se cuenta como SISTEMA")
    void sinRol() {
        actividad.aplicar(new ResumenTimeline("DEL-AC-5", "delivery.received", "RECIBIDA", null, null,
                Instant.parse("2026-01-07T08:30:00Z"), UUID.randomUUID().toString()));
        Integer eventos = jdbc.queryForObject(
                "SELECT eventos FROM actividad_rol WHERE hora = '2026-01-07T08:00:00Z' AND rol = 'SISTEMA'", Integer.class);
        assertThat(eventos).isEqualTo(1);
    }

    @Test
    @DisplayName("El eventId del resumen no choca con el del evento original ya aplicado por los KPI")
    void noChocaConKpi() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, now())", id);

        assertThat(actividad.aplicar(new ResumenTimeline("DEL-AC-6", "delivery.received", "RECIBIDA", "u", "OPERADOR",
                Instant.parse("2026-01-08T08:00:00Z"), id))).isTrue();
    }
}
