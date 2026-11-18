package cl.agrotrack.audit.aplicacion;

import java.time.Instant;

/**
 * Lo que audit publica en {@code audit.timeline} por cada evento nuevo, con
 * clave = codigo de la entrega. El topico es compactado: queda el ultimo
 * resumen por entrega.
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
