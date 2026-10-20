package cl.agrotrack.mqadmin.infraestructura.web;

import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.Topologia;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado de la topologia: lo declarado y lo que el broker reporta ahora
 * (mensajes encolados, consumidores). La tasa de mensajes en DLQ que pide
 * el enunciado sale de aqui: {@code totalEnDlq} mayor que cero es una alarma.
 */
@RestController
@RequestMapping("/api/mq")
public class TopologiaController {

    private final RabbitAdminService servicio;

    public TopologiaController(RabbitAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/topology")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Topologia topologia() {
        return servicio.topologia();
    }
}
