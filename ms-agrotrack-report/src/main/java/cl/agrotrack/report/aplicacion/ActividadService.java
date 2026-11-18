package cl.agrotrack.report.aplicacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Aplica los resumenes de {@code audit.timeline}: cuenta eventos por rol y
 * hora, y guarda el ultimo estado de cada entrega segun auditoria.
 * Idempotente: una reentrega del mismo resumen no suma dos veces.
 *
 * <p>audit.timeline es compactado (queda el ultimo resumen por entrega): las
 * cuentas son "desde que report-timeline empezo a leer". Releer el topico
 * desde el inicio no reconstruye la historia completa; para eso esta audit.
 */
@Service
public class ActividadService {

    private static final Logger log = LoggerFactory.getLogger(ActividadService.class);
    private static final String SIN_ROL = "SISTEMA";

    public record ActividadRol(String rol, long eventos) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ActividadService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true si se aplico; false si ya se habia aplicado. */
    @Transactional
    public boolean aplicar(ResumenTimeline r) {
        validar(r);
        Timestamp ahora = Timestamp.from(Instant.now(clock));
        if (jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                "timeline:" + r.eventId(), ahora) == 0) {
            log.info("[dup] resumen {} de {} ya aplicado", r.eventId(), r.codigo());
            return false;
        }
        String rol = r.actorRol() == null || r.actorRol().isBlank() ? SIN_ROL : r.actorRol().toUpperCase();
        jdbc.update("""
                INSERT INTO actividad_rol (hora, rol, eventos) VALUES (?, ?, 1)
                ON CONFLICT (hora, rol) DO UPDATE SET eventos = actividad_rol.eventos + 1
                """, Timestamp.from(r.ocurridoEn().truncatedTo(ChronoUnit.HOURS)), rol);
        jdbc.update("""
                INSERT INTO ultimo_estado (entrega_codigo, estado, ultimo_evento, ocurrido_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (entrega_codigo) DO UPDATE SET
                    estado = EXCLUDED.estado, ultimo_evento = EXCLUDED.ultimo_evento,
                    ocurrido_en = EXCLUDED.ocurrido_en, actualizado_en = EXCLUDED.actualizado_en
                WHERE ultimo_estado.ocurrido_en <= EXCLUDED.ocurrido_en
                """, r.codigo(), r.estado(), r.ultimoEvento(), Timestamp.from(r.ocurridoEn()), ahora);
        log.info("[actividad] {} {} por {}", r.ultimoEvento(), r.codigo(), rol);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ActividadRol> actividad(Rango rango) {
        return jdbc.query("""
                SELECT rol, SUM(eventos) AS eventos FROM actividad_rol
                WHERE hora >= ? AND hora < ?
                GROUP BY rol ORDER BY eventos DESC, rol
                """, (rs, i) -> new ActividadRol(rs.getString("rol"), rs.getLong("eventos")),
                Timestamp.from(rango.desde().truncatedTo(ChronoUnit.HOURS)), Timestamp.from(rango.hasta()));
    }

    /** Sin estos campos no se puede deduplicar ni ubicar: no se reintenta, va a la DLT. */
    static void validar(ResumenTimeline r) {
        if (r == null || r.codigo() == null || r.codigo().isBlank() || r.eventId() == null || r.ocurridoEn() == null) {
            throw new MensajeInvalidoException("Resumen de timeline incompleto: " + r);
        }
    }
}
