package cl.agrotrack.audit.infraestructura.web;

import cl.agrotrack.audit.aplicacion.AuditService;
import cl.agrotrack.audit.infraestructura.persistencia.EventoTimeline;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Solo lectura (enunciado, seccion 3). Admin y Auditor. */
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    public record EventoResponse(
            String eventId, String entregaCodigo, String tipo,
            String actorId, String actorNombre, String actorRol,
            Instant ocurridoEn, Instant recibidoEn,
            String traceId, String correlationId, String source,
            Map<String, Object> data) {

        static EventoResponse de(EventoTimeline e) {
            return new EventoResponse(e.getEventId(), e.getEntregaCodigo(), e.getTipo(),
                    e.getActorId(), e.getActorNombre(), e.getActorRol(),
                    e.getOcurridoEn(), e.getRecibidoEn(),
                    e.getTraceId(), e.getCorrelationId(), e.getSource(), e.getPayload());
        }
    }

    /** Trazabilidad de una entrega: quien registro, recibio, clasifico o despacho, en orden. */
    @GetMapping("/deliveries/{codigo}/timeline")
    public List<EventoResponse> timeline(@PathVariable String codigo) {
        return audit.timelineDe(codigo).stream().map(EventoResponse::de).toList();
    }

    /** Filtros de la pantalla: usuario, fechas, tipo de evento. */
    @GetMapping("/events")
    public List<EventoResponse> eventos(
            @RequestParam(required = false) String usuario,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta,
            @RequestParam(required = false) String tipo,
            @RequestParam(defaultValue = "100") int limite) {
        return audit.buscar(usuario, desde, hasta, tipo, limite).stream().map(EventoResponse::de).toList();
    }
}
