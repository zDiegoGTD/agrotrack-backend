package cl.agrotrack.kafkaadmin.infraestructura.web;

import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoTopico;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Todos los topicos del cluster (sin los internos de Kafka) con su configuracion actual. */
@RestController
@RequestMapping("/api/kafka")
public class TopicosController {

    private final KafkaAdminService servicio;

    public TopicosController(KafkaAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/topics")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<EstadoTopico> topicos() {
        return servicio.topicos();
    }
}
