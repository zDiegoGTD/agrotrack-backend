package cl.agrotrack.deliveries.infraestructura.persistencia;

import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "entrega")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Entrega {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_entrega")
    @SequenceGenerator(name = "seq_entrega", sequenceName = "seq_entrega", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 30, unique = true)
    private String codigo;

    @Column(name = "productor_id", nullable = false, length = 50)
    private String productorId;

    @Column(name = "producto_id", nullable = false)
    private Long productoId;

    @Column(name = "bodega_id", nullable = false)
    private Long bodegaId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cantidad;

    @Column(name = "peso_recibido", precision = 12, scale = 2)
    private BigDecimal pesoRecibido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoEntrega estado;

    @Column(name = "motivo_rechazo", length = 500)
    private String motivoRechazo;

    @Column(name = "fecha_registro", nullable = false)
    private Instant fechaRegistro;

    @Column(name = "fecha_recepcion")
    private Instant fechaRecepcion;

    @Column(name = "fecha_despacho")
    private Instant fechaDespacho;

    @Version
    private Long version;

    public Entrega(String codigo, String productorId, Long productoId, Long bodegaId, BigDecimal cantidad) {
        this.codigo = codigo;
        this.productorId = productorId;
        this.productoId = productoId;
        this.bodegaId = bodegaId;
        this.cantidad = cantidad;
        this.estado = EstadoEntrega.REGISTRADA;
        this.fechaRegistro = Instant.now();
    }

    /**
     * Aplica una transicion YA validada por la maquina de estados.
     * Guarda las fechas que despues alimentan el tiempo de ciclo.
     */
    public void transicionar(EstadoEntrega destino, BigDecimal pesoRecibido, String motivo, Instant cuando) {
        this.estado = destino;
        switch (destino) {
            case RECIBIDA -> {
                this.fechaRecepcion = cuando;
                if (pesoRecibido != null) {
                    this.pesoRecibido = pesoRecibido;
                }
            }
            case DESPACHADA -> this.fechaDespacho = cuando;
            case RECHAZADA -> this.motivoRechazo = motivo;
            default -> {
            }
        }
    }

    /** Lo que ocupa (u ocupo) en bodega: el peso real si ya se peso, si no lo declarado. */
    public BigDecimal cantidadEnBodega() {
        return pesoRecibido != null ? pesoRecibido : cantidad;
    }
}
