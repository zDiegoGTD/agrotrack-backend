package cl.agrotrack.catalog.aplicacion;

import cl.agrotrack.catalog.aplicacion.Dtos.BodegaRequest;
import cl.agrotrack.catalog.aplicacion.Dtos.BodegaResponse;
import cl.agrotrack.catalog.aplicacion.Dtos.ProductoRequest;
import cl.agrotrack.catalog.aplicacion.Dtos.ProductoResponse;
import cl.agrotrack.catalog.infraestructura.persistencia.Bodega;
import cl.agrotrack.catalog.infraestructura.persistencia.BodegaRepository;
import cl.agrotrack.catalog.infraestructura.persistencia.Producto;
import cl.agrotrack.catalog.infraestructura.persistencia.ProductoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** CRUD de productos y bodegas. La capacidad tiene su propio servicio por el reintento. */
@Service
@Transactional
public class CatalogService {

    private final ProductoRepository productos;
    private final BodegaRepository bodegas;

    public CatalogService(ProductoRepository productos, BodegaRepository bodegas) {
        this.productos = productos;
        this.bodegas = bodegas;
    }

    // ---- productos ----

    @Transactional(readOnly = true)
    public List<ProductoResponse> listarProductos(boolean soloActivos) {
        List<Producto> lista = soloActivos ? productos.findByActivoTrueOrderByNombre() : productos.findAll();
        return lista.stream().map(ProductoResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public ProductoResponse obtenerProducto(Long id) {
        return ProductoResponse.de(producto(id));
    }

    public ProductoResponse crearProducto(ProductoRequest req) {
        productos.findByCodigo(req.codigo()).ifPresent(p -> {
            throw new CodigoDuplicadoException(req.codigo());
        });
        Producto p = new Producto(req.codigo(), req.nombre(), req.unidadMedida(), req.tarifa());
        if (req.activo() != null) {
            p.setActivo(req.activo());
        }
        return ProductoResponse.de(productos.save(p));
    }

    public ProductoResponse actualizarProducto(Long id, ProductoRequest req) {
        Producto p = producto(id);
        productos.findByCodigo(req.codigo())
                .filter(otro -> !otro.getId().equals(id))
                .ifPresent(otro -> {
                    throw new CodigoDuplicadoException(req.codigo());
                });
        p.setCodigo(req.codigo());
        p.setNombre(req.nombre());
        p.setUnidadMedida(req.unidadMedida());
        p.setTarifa(req.tarifa());
        if (req.activo() != null) {
            p.setActivo(req.activo());
        }
        return ProductoResponse.de(p);
    }

    // ---- bodegas ----

    @Transactional(readOnly = true)
    public List<BodegaResponse> listarBodegas() {
        return bodegas.findAllByOrderByNombre().stream().map(BodegaResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public BodegaResponse obtenerBodega(Long id) {
        return BodegaResponse.de(bodega(id));
    }

    public BodegaResponse crearBodega(BodegaRequest req) {
        return BodegaResponse.de(bodegas.save(new Bodega(req.nombre(), req.ubicacion(), req.capacidadTotal())));
    }

    public BodegaResponse actualizarBodega(Long id, BodegaRequest req) {
        Bodega b = bodega(id);
        b.setNombre(req.nombre());
        b.setUbicacion(req.ubicacion());
        if (b.getCapacidadTotal().compareTo(req.capacidadTotal()) != 0) {
            b.redimensionar(req.capacidadTotal());
        }
        return BodegaResponse.de(b);
    }

    private Producto producto(Long id) {
        return productos.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Producto", id));
    }

    private Bodega bodega(Long id) {
        return bodegas.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Bodega", id));
    }

    /** Se traduce a 409. */
    public static class CodigoDuplicadoException extends RuntimeException {
        public CodigoDuplicadoException(String codigo) {
            super("Ya existe un producto con codigo " + codigo);
        }
    }
}
