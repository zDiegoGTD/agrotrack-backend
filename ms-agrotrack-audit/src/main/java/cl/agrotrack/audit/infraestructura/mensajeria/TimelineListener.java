package cl.agrotrack.audit.infraestructura.mensajeria;

import cl.agrotrack.audit.aplicacion.AuditService;
import cl.agrotrack.audit.infraestructura.persistencia.EventoTimeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consume {@code deliveries.events} y, por cada evento nuevo, republica un
 * resumen en {@code audit.timeline} con clave = codigo de la entrega.
 *
 * <p>audit.timeline es compactado: Kafka conserva el ultimo mensaje por
 * clave, asi que el topico entero es "el estado actual de cada entrega
 * segun auditoria", reconstruible sin tocar la base.
 */
@Component
public class TimelineListener {

    private static final Logger log = LoggerFactory.getLogger(TimelineListener.class);

    private final AuditService audit;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topicoTimeline;

    public TimelineListener(AuditService audit, KafkaTemplate<String, String> kafka, ObjectMapper json,
                            @Value("${agrotrack.kafka.topico-timeline}") String topicoTimeline) {
        this.audit = audit;
        this.kafka = kafka;
        this.json = json;
        this.topicoTimeline = topicoTimeline;
    }

    @KafkaListener(topics = "${agrotrack.kafka.topico-eventos}")
    public void onEvento(String cuerpo) {
        audit.registrar(cuerpo).ifPresent(this::republicarResumen);
    }

    private void republicarResumen(EventoTimeline e) {
        try {
            Map<String, Object> resumen = new LinkedHashMap<>();
            resumen.put("codigo", e.getEntregaCodigo());
            resumen.put("ultimoEvento", e.getTipo());
            resumen.put("estado", e.getPayload().get("estado"));
            resumen.put("actorId", e.getActorId());
            resumen.put("actorRol", e.getActorRol());
            resumen.put("ocurridoEn", e.getOcurridoEn().toString());
            resumen.put("eventId", e.getEventId());
            kafka.send(topicoTimeline, e.getEntregaCodigo(), json.writeValueAsString(resumen));
        } catch (Exception ex) {
            // El timeline en base ya quedo escrito; el resumen es derivado y
            // se puede regenerar. No se tumba el consumo por esto.
            log.warn("No se pudo republicar el resumen de {} en {}: {}", e.getEntregaCodigo(), topicoTimeline, ex.getMessage());
        }
    }
}
