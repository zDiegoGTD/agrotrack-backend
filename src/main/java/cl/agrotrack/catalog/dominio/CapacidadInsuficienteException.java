package cl.agrotrack.catalog.dominio;

import java.math.BigDecimal;

/** La bodega no tiene espacio para la cantidad pedida. Se traduce a 409. */
public class CapacidadInsuficienteException extends RuntimeException {

    private final BigDecimal disponible;
    private final BigDecimal solicitada;

    public CapacidadInsuficienteException(BigDecimal disponible, BigDecimal solicitada) {
        super("Capacidad insuficiente: disponible " + disponible + ", solicitada " + solicitada);
        this.disponible = disponible;
        this.solicitada = solicitada;
    }

    public BigDecimal disponible() {
        return disponible;
    }

    public BigDecimal solicitada() {
        return solicitada;
    }
}
