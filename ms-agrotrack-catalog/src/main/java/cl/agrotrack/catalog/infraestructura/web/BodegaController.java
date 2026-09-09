package cl.agrotrack.catalog.infraestructura.web;

import cl.agrotrack.catalog.aplicacion.CapacidadService;
import cl.agrotrack.catalog.aplicacion.CatalogService;
import cl.agrotrack.catalog.aplicacion.Dtos.BodegaRequest;
import cl.agrotrack.catalog.aplicacion.Dtos.BodegaResponse;
import cl.agrotrack.catalog.aplicacion.Dtos.CapacidadRequest;
import cl.agrotrack.catalog.aplicacion.Dtos.CapacidadResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalog/bodegas")
public class BodegaController {

    private final CatalogService catalog;
    private final CapacidadService capacidad;

    public BodegaController(CatalogService catalog, CapacidadService capacidad) {
        this.catalog = catalog;
        this.capacidad = capacidad;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<BodegaResponse> listar() {
        return catalog.listarBodegas();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public BodegaResponse obtener(@PathVariable Long id) {
        return catalog.obtenerBodega(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public BodegaResponse crear(@Valid @RequestBody BodegaRequest req) {
        return catalog.crearBodega(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BodegaResponse actualizar(@PathVariable Long id, @Valid @RequestBody BodegaRequest req) {
        return catalog.actualizarBodega(id, req);
    }

    /**
     * Los dos endpoints de capacidad los llama ms-agrotrack-deliveries al
     * ejecutar una transicion, con el token del operador que la ejecuta.
     * Por eso los roles son los que pueden recibir o rechazar un lote.
     */
    @PostMapping("/{id}/capacidad/reservar")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public CapacidadResponse reservar(@PathVariable Long id, @Valid @RequestBody CapacidadRequest req) {
        return capacidad.reservar(id, req);
    }

    @PostMapping("/{id}/capacidad/liberar")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public CapacidadResponse liberar(@PathVariable Long id, @Valid @RequestBody CapacidadRequest req) {
        return capacidad.liberar(id, req);
    }
}
