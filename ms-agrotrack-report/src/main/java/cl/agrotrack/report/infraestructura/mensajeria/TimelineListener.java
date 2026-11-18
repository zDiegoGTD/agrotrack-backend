package cl.agrotrack.report.infraestructura.mensajeria;

import cl.agrotrack.report.aplicacion.ActividadService;
import cl.agrotrack.report.aplicacion.ResumenTimeline;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code report-timeline} sobre {@code audit.timeline}, el
 * topico que publica auditoria. Va en un grupo propio: su avance es
 * independiente del de {@code report-consumer} sobre deliveries.events.
 */
@Component
public class TimelineListener {

    private final ActividadService actividad;

    public TimelineListener(ActividadService actividad) {
        this.actividad = actividad;
    }

    @KafkaListener(topics = "${agrotrack.kafka.topico-timeline}", groupId = "${agrotrack.kafka.grupo-timeline}",
            clientIdPrefix = "${spring.application.name}-timeline")
    public void onResumen(ResumenTimeline resumen) {
        actividad.aplicar(resumen);
    }
}
