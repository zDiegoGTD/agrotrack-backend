package cl.agrotrack.report.infraestructura.mensajeria;

import cl.agrotrack.report.aplicacion.Envelope;
import cl.agrotrack.report.aplicacion.KpiService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code report-consumer}, independiente de {@code audit-consumer}:
 * cada uno lee deliveries.events completo y a su propio ritmo. Si este se
 * cae, auditoria ni se entera. Solo traduce el mensaje: la logica esta en KpiService.
 */
@Component
public class KpiListener {

    private final KpiService kpis;

    public KpiListener(KpiService kpis) {
        this.kpis = kpis;
    }

    @KafkaListener(topics = "${agrotrack.kafka.topico-eventos}", groupId = "${agrotrack.kafka.grupo}",
            clientIdPrefix = "${spring.application.name}-eventos")
    public void onEvento(Envelope evento) {
        kpis.aplicar(evento);
    }
}
