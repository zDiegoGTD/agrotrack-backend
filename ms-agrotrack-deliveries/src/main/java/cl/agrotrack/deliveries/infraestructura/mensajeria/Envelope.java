package cl.agrotrack.deliveries.infraestructura.mensajeria;

import java.time.Instant;
import java.util.Map;

/**
 * El sobre comun de todo mensaje, Kafka o RabbitMQ.
 * Contrato en docs/02-contrato-de-eventos.md. Identico en cada servicio.
 *
 * @param specVersion   version del formato ("1.0")
 * @param type          que paso (hechos, en pasado: delivery.received) o
 *                      que hacer (comandos, imperativo: email.send)
 * @param eventId       UUID v7; clave de idempotencia del consumidor
 * @param occurredAt    cuando paso en el dominio, UTC
 * @param traceId       une todos los mensajes de un mismo request
 * @param correlationId une todos los mensajes de una misma entrega
 * @param source        servicio emisor
 * @param subject       entidad afectada; clave de particion en Kafka
 * @param actor         quien lo provoco
 * @param data          payload especifico del tipo
 */
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

    public static final String SPEC_VERSION = "1.0";

    public record Actor(String userId, String nombre, String role) {
    }
}
