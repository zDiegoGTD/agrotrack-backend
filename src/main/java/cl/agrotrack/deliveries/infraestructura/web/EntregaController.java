package cl.agrotrack.deliveries.infraestructura.web;

import cl.agrotrack.deliveries.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.deliveries.aplicacion.Dtos.EntregaResponse;
import cl.agrotrack.deliveries.aplicacion.Dtos.RegistrarEntregaRequest;
import cl.agrotrack.deliveries.aplicacion.Dtos.TransicionesResponse;
import cl.agrotrack.deliveries.aplicacion.EntregaService;
import cl.agrotrack.deliveries.config.Actores;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Endpoints de la seccion 5 del enunciado. */
@RestController
@RequestMapping("/api/deliveries")
public class EntregaController {

    private final EntregaService servicio;

    public EntregaController(EntregaService servicio) {
        this.servicio = servicio;
    }

    /** T1. El productor registra las suyas; el jefe de acopio puede registrar en nombre de uno. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CLIENTE')")
    public EntregaResponse registrar(@Valid @RequestBody RegistrarEntregaRequest req, Authentication auth) {
        return servicio.registrar(req, Actores.desde(auth));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public EntregaResponse obtener(@PathVariable Long id, Authentication auth) {
        return servicio.obtener(id, Actores.desde(auth));
    }

    /**
     * T2..T8. La anotacion filtra al auditor y al productor antes de llegar
     * al servicio; la maquina de estados vuelve a comprobarlo igual, porque
     * la regla vive en el dominio, no en la anotacion.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public EntregaResponse cambiarEstado(@PathVariable Long id,
                                         @Valid @RequestBody CambioEstadoRequest req,
                                         Authentication auth) {
        return servicio.cambiarEstado(id, req, Actores.desde(auth));
    }

    /** GET /api/deliveries?status=RECIBIDA&from=2026-09-01T00:00:00Z&to=... */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<EntregaResponse> listar(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Authentication auth) {
        EstadoEntrega estado = status == null || status.isBlank() ? null : EstadoEntrega.parse(status);
        return servicio.listar(estado, from, to, Actores.desde(auth));
    }

    /** Para que la UI muestre solo los botones que aplican a este usuario y este estado. */
    @GetMapping("/{id}/transiciones")
    @PreAuthorize("isAuthenticated()")
    public TransicionesResponse transiciones(@PathVariable Long id, Authentication auth) {
        return servicio.transicionesDisponibles(id, Actores.desde(auth));
    }
}
