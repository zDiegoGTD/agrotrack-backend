package cl.agrotrack.report.aplicacion;

import cl.agrotrack.report.aplicacion.ReportService.Kpis;
import cl.agrotrack.report.aplicacion.ReportService.TopProducto;
import cl.agrotrack.report.soporte.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KpiServiceIT extends PostgresIT {

    @Autowired KpiService kpis;
    @Autowired ReportService report;
    @Autowired JdbcTemplate jdbc;

    static final Instant AHORA = Instant.now();

    private static String evento(String type, String codigo, Instant cuando, long bodega, long producto, String estado, String extra) {
        return """
                {"specVersion":"1.0","type":"%s","eventId":"%s","occurredAt":"%s",
                 "traceId":"t","correlationId":"c","source":"ms-agrotrack-deliveries","subject":"%s",
                 "actor":{"userId":"op-1","nombre":"Jefa","role":"OPERADOR"},
                 "data":{"codigo":"%s","bodegaId":%d,"productoId":%d,"productorId":"prod-1","cantidad":100,"estado":"%s"%s}}
                """.formatted(type, UUID.randomUUID(), cuando, codigo, codigo, bodega, producto, estado, extra);
    }

    private String registrada(String codigo, Instant cuando, long bodega, long producto) {
        return evento("delivery.registered", codigo, cuando, bodega, producto, "REGISTRADA", ",\"fechaRegistro\":\"" + cuando + "\"");
    }

    private String recibida(String codigo, Instant cuando, long bodega, long producto, String peso) {
        return evento("delivery.received", codigo, cuando, bodega, producto, "RECIBIDA", ",\"pesoRecibido\":" + peso);
    }

    private String despachada(String codigo, Instant cuando, long bodega, long producto) {
        return evento("delivery.dispatched", codigo, cuando, bodega, producto, "DESPACHADA", "");
    }

    @Test
    @DisplayName("Tres recepciones en la misma hora y bodega -> recibidas = 3 en esa hora")
    void entregasPorHora() {
        Instant h = AHORA.minusSeconds(3600);
        kpis.aplicar(recibida("DEL-R-1", h, 7, 1, "10"));
        kpis.aplicar(recibida("DEL-R-2", h.plusSeconds(60), 7, 1, "10"));
        kpis.aplicar(recibida("DEL-R-3", h.plusSeconds(120), 7, 1, "10"));

        Kpis k = report.kpis(Rango.parse("last24h", Clock.fixed(AHORA, ZoneOffset.UTC)));
        assertThat(k.entregasPorHora()).anySatisfy(fila -> {
            assertThat(fila.hora()).isEqualTo(h.truncatedTo(java.time.temporal.ChronoUnit.HOURS));
            assertThat(fila.recibidas()).isGreaterThanOrEqualTo(3);
        });
        assertThat(k.estadosActivos().get("RECIBIDA")).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Registro a las 10:00 y despacho a las 11:30 -> 90 minutos de ciclo, y deja de estar activa")
    void tiempoDeCiclo() {
        Instant t0 = AHORA.minusSeconds(7200);
        kpis.aplicar(registrada("DEL-C-1", t0, 8, 2));
        kpis.aplicar(recibida("DEL-C-1", t0.plusSeconds(600), 8, 2, "95"));
        kpis.aplicar(despachada("DEL-C-1", t0.plusSeconds(5400), 8, 2));

        Integer minutos = jdbc.queryForObject("SELECT minutos_ciclo FROM ciclo_entrega WHERE entrega_codigo = 'DEL-C-1'", Integer.class);
        assertThat(minutos).isEqualTo(90);

        Kpis k = report.kpis(Rango.parse("last24h", Clock.fixed(AHORA, ZoneOffset.UTC)));
        assertThat(k.entregasCerradas()).isGreaterThanOrEqualTo(1);
        assertThat(k.tiempoCicloPromedioMin()).isNotNull();
        String estado = jdbc.queryForObject("SELECT estado FROM ciclo_entrega WHERE entrega_codigo = 'DEL-C-1'", String.class);
        assertThat(estado).isEqualTo("DESPACHADA");
    }

    @Test
    @DisplayName("El mismo eventId dos veces suma una sola vez")
    void idempotente() {
        String cuerpo = recibida("DEL-I-1", AHORA.minusSeconds(60), 9, 3, "50");
        assertThat(kpis.aplicar(cuerpo)).isTrue();
        assertThat(kpis.aplicar(cuerpo)).isFalse();

        Integer n = jdbc.queryForObject(
                "SELECT recibidas FROM kpi_entregas_hora WHERE bodega_id = 9", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("Productos mas recibidos en el rango, ordenados por cantidad de lotes")
    void topProductos() {
        Instant h = AHORA.minusSeconds(300);
        kpis.aplicar(recibida("DEL-T-1", h, 5, 42, "10"));
        kpis.aplicar(recibida("DEL-T-2", h, 5, 42, "20"));
        kpis.aplicar(recibida("DEL-T-3", h, 5, 43, "99"));

        List<TopProducto> top = report.topProductos(Rango.parse("last7d", Clock.fixed(AHORA, ZoneOffset.UTC)), 10);
        assertThat(top.get(0).productoId()).isEqualTo(42L);
        assertThat(top.get(0).entregas()).isGreaterThanOrEqualTo(2);
        assertThat(top).anySatisfy(t -> assertThat(t.productoId()).isEqualTo(43L));
    }

    @Test
    @DisplayName("Un evento recibido antes que su registro igual crea la fila del ciclo")
    void ordenAlterado() {
        kpis.aplicar(recibida("DEL-O-1", AHORA.minusSeconds(60), 6, 1, "10"));
        String estado = jdbc.queryForObject("SELECT estado FROM ciclo_entrega WHERE entrega_codigo = 'DEL-O-1'", String.class);
        assertThat(estado).isEqualTo("RECIBIDA");
    }

    @Test
    @DisplayName("JSON invalido: MensajeInvalidoException")
    void invalido() {
        assertThatThrownBy(() -> kpis.aplicar("{roto")).isInstanceOf(MensajeInvalidoException.class);
    }

    @Test
    @DisplayName("Rango: last24h, last7d validos; otros formatos 400")
    void rangos() {
        Clock c = Clock.fixed(AHORA, ZoneOffset.UTC);
        assertThat(Rango.parse("last24h", c).desde()).isEqualTo(AHORA.minusSeconds(86400));
        assertThat(Rango.parse("LAST7D", c).desde()).isEqualTo(AHORA.minusSeconds(7 * 86400));
        assertThat(Rango.parse(null, c).etiqueta()).isEqualTo("last24h");
        assertThatThrownBy(() -> Rango.parse("ayer", c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rango.parse("last999d", c)).isInstanceOf(IllegalArgumentException.class);
    }
}
