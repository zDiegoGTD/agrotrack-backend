package cl.agrotrack.catalog.infraestructura.web;

import cl.agrotrack.catalog.aplicacion.CatalogService;
import cl.agrotrack.catalog.aplicacion.Dtos.ProductoRequest;
import cl.agrotrack.catalog.aplicacion.Dtos.ProductoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Productos del catalogo. Se expone tambien como /api/catalog/services,
 * que es la ruta que usa el enunciado en sus ejemplos.
 *
 * <p>Leer lo puede cualquier autenticado: el productor necesita la lista
 * para registrar una entrega. Escribir es solo del Admin (seccion 3).
 */
@RestController
@RequestMapping({"/api/catalog/productos", "/api/catalog/services"})
public class ProductoController {

    private final CatalogService catalog;

    public ProductoController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ProductoResponse> listar(@RequestParam(defaultValue = "true") boolean soloActivos) {
        return catalog.listarProductos(soloActivos);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ProductoResponse obtener(@PathVariable Long id) {
        return catalog.obtenerProducto(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ProductoResponse crear(@Valid @RequestBody ProductoRequest req) {
        return catalog.crearProducto(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductoResponse actualizar(@PathVariable Long id, @Valid @RequestBody ProductoRequest req) {
        return catalog.actualizarProducto(id, req);
    }
}
