package cl.agrotrack.report.aplicacion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Lado de escritura: aplica cada evento de deliveries.events a las tablas
 * de agregacion. Una transaccion por evento; la marca de idempotencia va
 * dentro, asi que "sumado" y "marcado" no se separan nunca.
 */
@Service
public class KpiService {

    private static final Logger log = LoggerFactory.getLogger(KpiService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public KpiService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** @return true si el evento se aplico; false si ya se habia procesado. */
    @Transactional
    public boolean aplicar(String cuerpo) {
        Envelope env = parsear(cuerpo);
        if (!marcarSiEsNuevo(env.eventId())) {
            log.info("[dup] {} {} ya aplicado", env.type(), env.eventId());
            return false;
        }

        Instant hora = env.occurredAt().truncatedTo(ChronoUnit.HOURS);
        Long bodegaId = numero(env.dato("bodegaId"));
        Long productoId = numero(env.dato("productoId"));
        String codigo = env.subject();
        String estado = env.dato("estado");
        Timestamp ahora = Timestamp.from(Instant.now(clock));

        switch (env.type()) {
            case "delivery.registered" -> {
                sumar(hora, bodegaId, "registradas");
                jdbc.update("""
                        INSERT INTO ciclo_entrega (entrega_codigo, bodega_id, producto_id, productor_id, peso, estado,
                                                   fecha_registro, actualizado_en)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (entrega_codigo) DO UPDATE SET
                            bodega_id = EXCLUDED.bodega_id, producto_id = EXCLUDED.producto_id,
                            productor_id = EXCLUDED.productor_id, estado = EXCLUDED.estado,
                            fecha_registro = EXCLUDED.fecha_registro, actualizado_en = EXCLUDED.actualizado_en
                        """,
                        codigo, bodegaId, productoId, env.dato("productorId"), decimal(env.dato("cantidad")),
                        estado, Timestamp.from(env.occurredAt()), ahora);
            }
            case "delivery.received" -> {
                sumar(hora, bodegaId, "recibidas");
                asegurarCiclo(codigo, bodegaId, productoId, env, ahora);
                jdbc.update("""
                        UPDATE ciclo_entrega SET estado = ?, fecha_recepcion = ?, peso = COALESCE(?, peso), actualizado_en = ?
                        WHERE entrega_codigo = ?
                        """, estado, Timestamp.from(env.occurredAt()), decimal(env.dato("pesoRecibido")), ahora, codigo);
            }
            case "delivery.dispatched" -> {
                sumar(hora, bodegaId, "despachadas");
                asegurarCiclo(codigo, bodegaId, productoId, env, ahora);
                jdbc.update("""
                        UPDATE ciclo_entrega SET estado = ?, fecha_cierre = ?,
                            minutos_ciclo = CASE WHEN fecha_registro IS NULL THEN NULL
                                                 ELSE CAST(EXTRACT(EPOCH FROM (CAST(? AS TIMESTAMPTZ) - fecha_registro)) / 60 AS INTEGER) END,
                            actualizado_en = ?
                        WHERE entrega_codigo = ?
                        """, estado, Timestamp.from(env.occurredAt()), Timestamp.from(env.occurredAt()), ahora, codigo);
            }
            case "delivery.rejected" -> {
                sumar(hora, bodegaId, "rechazadas");
                asegurarCiclo(codigo, bodegaId, productoId, env, ahora);
                jdbc.update("UPDATE ciclo_entrega SET estado = ?, fecha_cierre = ?, actualizado_en = ? WHERE entrega_codigo = ?",
                        estado, Timestamp.from(env.occurredAt()), ahora, codigo);
            }
            case "delivery.classifying", "delivery.dispatching" -> {
                asegurarCiclo(codigo, bodegaId, productoId, env, ahora);
                jdbc.update("UPDATE ciclo_entrega SET estado = ?, actualizado_en = ? WHERE entrega_codigo = ?",
                        estado, ahora, codigo);
            }
            default -> log.debug("Evento {} no afecta KPIs; solo se marca", env.type());
        }
        log.info("[kpi] {} {} aplicado", env.type(), codigo);
        return true;
    }

    // ---- helpers SQL ----

    /** UPSERT del contador de la hora/bodega. La columna viene de una lista cerrada, no del mensaje. */
    private void sumar(Instant hora, Long bodegaId, String columna) {
        if (!columna.matches("registradas|recibidas|despachadas|rechazadas")) {
            throw new IllegalArgumentException(columna);
        }
        jdbc.update("""
                INSERT INTO kpi_entregas_hora (hora, bodega_id, %1$s) VALUES (?, ?, 1)
                ON CONFLICT (hora, bodega_id) DO UPDATE SET %1$s = kpi_entregas_hora.%1$s + 1
                """.formatted(columna), Timestamp.from(hora), bodegaId == null ? 0L : bodegaId);
    }

    /** Si el evento de registro se perdio (o llego despues), la fila igual tiene que existir. */
    private void asegurarCiclo(String codigo, Long bodegaId, Long productoId, Envelope env, Timestamp ahora) {
        jdbc.update("""
                INSERT INTO ciclo_entrega (entrega_codigo, bodega_id, producto_id, productor_id, peso, estado, fecha_registro, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (entrega_codigo) DO NOTHING
                """, codigo, bodegaId, productoId, env.dato("productorId"), decimal(env.dato("cantidad")),
                env.dato("estado"), fecha(env.dato("fechaRegistro")), ahora);
    }

    private boolean marcarSiEsNuevo(String eventId) {
        return jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, Timestamp.from(Instant.now(clock))) == 1;
    }

    // ---- parseo ----

    Envelope parsear(String cuerpo) {
        Envelope env;
        try {
            env = json.readValue(cuerpo, Envelope.class);
        } catch (IOException e) {
            throw new MensajeInvalidoException("JSON invalido: " + resumen(cuerpo), e);
        }
        if (env.eventId() == null || env.type() == null || env.subject() == null || env.occurredAt() == null) {
            throw new MensajeInvalidoException("Evento incompleto: " + resumen(cuerpo));
        }
        return env;
    }

    private static Long numero(String s) {
        try {
            return s == null ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String s) {
        try {
            return s == null ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Timestamp fecha(String iso) {
        try {
            return iso == null ? null : Timestamp.from(Instant.parse(iso));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String resumen(String s) {
        return s == null ? "null" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
