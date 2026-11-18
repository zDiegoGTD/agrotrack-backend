package cl.agrotrack.report.aplicacion;

import java.time.Instant;

/**
 * Lo que ms-agrotrack-audit publica en {@code audit.timeline} (clave = codigo
 * de la entrega). Mismo JSON que el record de audit; cada servicio tiene el suyo.
 */
public record ResumenTimeline(
        String codigo,
        String ultimoEvento,
        String estado,
        String actorId,
        String actorRol,
        Instant ocurridoEn,
        String eventId) {
}
