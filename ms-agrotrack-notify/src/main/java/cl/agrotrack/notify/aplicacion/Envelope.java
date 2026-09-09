package cl.agrotrack.notify.aplicacion;

import java.time.Instant;
import java.util.Map;

/** El sobre comun (docs/02-contrato-de-eventos.md). Identico al del emisor. */
public record Envelope(
        String specVersion,
        String type,
        String eventId,
        Instant occurredAt,
        String traceId,
        String correlationId,
        String source,
        String subject,
        Actor actor,
        Map<String, Object> data) {

    public record Actor(String userId, String nombre, String role) {
    }

    /** Lectura tolerante del payload: los campos pueden faltar en versiones futuras. */
    public String dato(String clave) {
        Object v = data == null ? null : data.get(clave);
        return v == null ? null : v.toString();
    }
}
