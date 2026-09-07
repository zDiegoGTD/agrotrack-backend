package cl.agrotrack.deliveries.aplicacion;

import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record RegistrarEntregaRequest(
            @NotNull Long productoId,
            @NotNull Long bodegaId,
            @NotNull @Positive BigDecimal cantidad) {
    }

    /**
     * Body de PUT /api/deliveries/{id}/status. {@code status} es String y no
     * el enum para aceptar "EN_CLASIFICACIÓN" con tilde, como lo escribe el
     * enunciado; lo normaliza {@link EstadoEntrega#parse}.
     */
    public record CambioEstadoRequest(
            @NotBlank String status,
            @Positive BigDecimal pesoRecibido,
            @Size(max = 500) String motivo) {
    }

    public record EntregaResponse(
            Long id, String codigo, String productorId, Long productoId, Long bodegaId,
            BigDecimal cantidad, BigDecimal pesoRecibido,
            EstadoEntrega estado, String estadoEtiqueta, boolean terminal,
            String motivoRechazo,
            Instant fechaRegistro, Instant fechaRecepcion, Instant fechaDespacho) {

        public static EntregaResponse de(Entrega e) {
            return new EntregaResponse(e.getId(), e.getCodigo(), e.getProductorId(), e.getProductoId(),
                    e.getBodegaId(), e.getCantidad(), e.getPesoRecibido(),
                    e.getEstado(), e.getEstado().etiqueta(), e.getEstado().esTerminal(),
                    e.getMotivoRechazo(), e.getFechaRegistro(), e.getFechaRecepcion(), e.getFechaDespacho());
        }
    }

    /** Que puede hacer el actor actual con esta entrega. Para que la UI habilite botones. */
    public record TransicionesResponse(EstadoEntrega actual, List<EstadoEntrega> permitidas) {
    }
}
