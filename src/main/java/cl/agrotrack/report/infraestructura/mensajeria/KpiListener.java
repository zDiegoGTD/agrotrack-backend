package cl.agrotrack.report.infraestructura.mensajeria;

import cl.agrotrack.report.aplicacion.KpiService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code report-consumer}, independiente de {@code audit-consumer}:
 * cada uno lee deliveries.events completo y a su propio ritmo. Si este se
 * cae, auditoria ni se entera.
 */
@Component
public class KpiListener {

    private final KpiService kpis;

    public KpiListener(KpiService kpis) {
        this.kpis = kpis;
    }

    @KafkaListener(topics = "${agrotrack.kafka.topico-eventos}")
    public void onEvento(String cuerpo) {
        kpis.aplicar(cuerpo);
    }
}
