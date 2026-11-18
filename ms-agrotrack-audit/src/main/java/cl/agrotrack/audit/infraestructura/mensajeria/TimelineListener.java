package cl.agrotrack.audit.infraestructura.mensajeria;

import cl.agrotrack.audit.aplicacion.AuditService;
import cl.agrotrack.audit.aplicacion.Envelope;
import cl.agrotrack.audit.aplicacion.ResumenTimeline;
import cl.agrotrack.audit.infraestructura.persistencia.EventoTimeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consume {@code deliveries.events} y, por cada evento nuevo, republica un
 * {@link ResumenTimeline} en {@code audit.timeline} con clave = codigo de la entrega.
 *
 * <p>audit.timeline es compactado: Kafka conserva el ultimo mensaje por
 * clave, asi que el topico entero es "el estado actual de cada entrega
 * segun auditoria", reconstruible sin tocar la base.
 */
@Component
public class TimelineListener {

    private static final Logger log = LoggerFactory.getLogger(TimelineListener.class);

    private final AuditService audit;
    private final KafkaTemplate<String, Object> kafka;
    private final String topicoTimeline;

    public TimelineListener(AuditService audit, KafkaTemplate<String, Object> kafka,
                            @Value("${agrotrack.kafka.topico-timeline}") String topicoTimeline) {
        this.audit = audit;
        this.kafka = kafka;
        this.topicoTimeline = topicoTimeline;
    }

    @KafkaListener(topics = "${agrotrack.kafka.topico-eventos}", groupId = "${agrotrack.kafka.grupo}")
    public void onEvento(Envelope evento) {
        audit.registrar(evento).ifPresent(this::republicarResumen);
    }

    private void republicarResumen(EventoTimeline e) {
        Object estado = e.getPayload() == null ? null : e.getPayload().get("estado");
        ResumenTimeline resumen = new ResumenTimeline(
                e.getEntregaCodigo(), e.getTipo(), estado == null ? null : estado.toString(),
                e.getActorId(), e.getActorRol(), e.getOcurridoEn(), e.getEventId());
        // El timeline en base ya quedo escrito; el resumen es derivado y se
        // puede regenerar. No se tumba el consumo por esto: si se relanzara, el
        // reintento veria el evento como duplicado y el resumen se perderia igual.
        try {
            kafka.send(topicoTimeline, e.getEntregaCodigo(), resumen)
                    .whenComplete((ok, ex) -> {
                        if (ex != null) {
                            avisarNoPublicado(e, ex);
                        }
                    });
        } catch (RuntimeException ex) {
            avisarNoPublicado(e, ex);
        }
    }

    private void avisarNoPublicado(EventoTimeline e, Throwable ex) {
        log.warn("No se pudo republicar el resumen de {} ({}) en {}: {}",
                e.getEntregaCodigo(), e.getEventId(), topicoTimeline, ex.getMessage());
    }
}
