package cl.agrotrack.deliveries.aplicacion;

import java.math.BigDecimal;

/**
 * Lo que deliveries necesita de catalog. Interfaz para poder probar el
 * servicio con un doble sin levantar HTTP.
 */
public interface CatalogClient {

    /** @throws Excepciones.RecursoNoEncontrado si el producto no existe o esta inactivo */
    void verificarProducto(Long productoId);

    /** @throws Excepciones.RecursoNoEncontrado si la bodega no existe */
    void verificarBodega(Long bodegaId);

    /**
     * Descuenta capacidad al RECIBIR.
     *
     * @throws Excepciones.CapacidadInsuficiente si no alcanza (catalog responde 409)
     */
    void reservarCapacidad(Long bodegaId, BigDecimal cantidad, String entregaCodigo);

    /** Devuelve capacidad al RECHAZAR un lote ya recibido. */
    void liberarCapacidad(Long bodegaId, BigDecimal cantidad, String entregaCodigo);
}
