package cl.agrotrack.catalog.aplicacion;

import cl.agrotrack.catalog.infraestructura.persistencia.Bodega;
import cl.agrotrack.catalog.infraestructura.persistencia.Producto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Contratos de entrada y salida de la API de catalogo. */
public final class Dtos {

    private Dtos() {
    }

    public record ProductoRequest(
            @NotBlank @Size(max = 30) String codigo,
            @NotBlank @Size(max = 120) String nombre,
            @NotBlank @Size(max = 10) String unidadMedida,
            @NotNull @PositiveOrZero BigDecimal tarifa,
            Boolean activo) {
    }

    public record ProductoResponse(
            Long id, String codigo, String nombre, String unidadMedida, BigDecimal tarifa, boolean activo) {

        public static ProductoResponse de(Producto p) {
            return new ProductoResponse(p.getId(), p.getCodigo(), p.getNombre(),
                    p.getUnidadMedida(), p.getTarifa(), p.isActivo());
        }
    }

    public record BodegaRequest(
            @NotBlank @Size(max = 120) String nombre,
            @Size(max = 200) String ubicacion,
            @NotNull @Positive BigDecimal capacidadTotal) {
    }

    public record BodegaResponse(
            Long id, String nombre, String ubicacion,
            BigDecimal capacidadTotal, BigDecimal capacidadDisponible) {

        public static BodegaResponse de(Bodega b) {
            return new BodegaResponse(b.getId(), b.getNombre(), b.getUbicacion(),
                    b.getCapacidadTotal(), b.getCapacidadDisponible());
        }
    }

    /** Reserva o liberacion de capacidad, siempre a nombre de una entrega. */
    public record CapacidadRequest(
            @NotNull @Positive BigDecimal cantidad,
            @NotBlank @Size(max = 30) String entregaCodigo) {
    }

    public record CapacidadResponse(Long bodegaId, BigDecimal capacidadDisponible, BigDecimal capacidadTotal) {

        public static CapacidadResponse de(Bodega b) {
            return new CapacidadResponse(b.getId(), b.getCapacidadDisponible(), b.getCapacidadTotal());
        }
    }
}
