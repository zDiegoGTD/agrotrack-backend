package cl.agrotrack.audit.infraestructura.persistencia;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * La tabla processed_events haciendo el trabajo: un INSERT con
 * ON CONFLICT DO NOTHING devuelve 0 filas si el eventId ya estaba.
 * Va dentro de la misma transaccion que la escritura del timeline, asi
 * que "marcado" y "procesado" son atomicos.
 */
@Component
public class RegistroIdempotente {

    private final JdbcTemplate jdbc;

    public RegistroIdempotente(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true si es la primera vez (y queda marcado); false si ya se proceso. */
    public boolean marcarSiEsNuevo(String eventId) {
        int filas = jdbc.update(
                "INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING",
                eventId, java.sql.Timestamp.from(Instant.now()));
        return filas == 1;
    }
}
